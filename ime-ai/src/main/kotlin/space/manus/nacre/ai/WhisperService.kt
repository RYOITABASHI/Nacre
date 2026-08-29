package space.manus.nacre.ai

import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.*

/**
 * Speech-to-text service using sherpa-onnx SenseVoice + Silero VAD.
 *
 * Runs in a separate process (android:process=":whisper") for crash isolation.
 * AIDL interface (IWhisperService/IWhisperCallback) is preserved for compatibility.
 *
 * Falls back to Android SpeechRecognizer API if model is unavailable.
 */
class WhisperService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isRecognizing = false
    @Volatile
    private var recordingJob: Job? = null
    @Volatile
    private var textBuffer = StringBuilder()
    @Volatile
    private var continuousCallback: IWhisperCallback? = null

    // Opt-in cloud ASR: when active, the utterance audio is buffered during
    // continuous recording and sent to the cloud on stop for a high-accuracy
    // final result (replacing the on-device transcription). Off by default.
    @Volatile
    private var cloudAsrActive = false
    private val cloudBuffer = java.util.ArrayList<FloatArray>()

    private var sherpaRecognizer: SherpaRecognizer? = null

    // Deterministic hallucination filtering for individual ASR segments. Cheap,
    // stateless, and catches known SenseVoice/whisper-style artifacts (e.g. the
    // model transcribing silence as "ご視聴ありがとうございました") before they
    // ever reach the text buffer that gets committed via IWhisperCallback.
    private val postProcessor = PostProcessor()

    private val binder = object : IWhisperService.Stub() {

        override fun isModelLoaded(): Boolean {
            return sherpaRecognizer?.isReady() == true
        }

        override fun isRecognizing(): Boolean {
            return this@WhisperService.isRecognizing
        }

        override fun loadModel(modelPath: String) {
            writeDiag("loadModel called: $modelPath")
            scope.launch {
                try {
                    // modelPath format: "modelDir|vadModelPath"
                    val parts = modelPath.split("|", limit = 2)
                    val modelDir = parts[0]
                    val vadPath = if (parts.size > 1) parts[1] else ""
                    writeDiag("Loading SenseVoice: dir=$modelDir, vad=$vadPath")
                    val modelFile = pickSenseVoiceModelFile(modelDir)
                    val vadFile = java.io.File(vadPath)
                    writeDiag("sensevoice model=${modelFile?.name} exists=${modelFile?.exists()} size=${modelFile?.length() ?: 0}")
                    writeDiag("silero_vad.onnx exists=${vadFile.exists()} size=${vadFile.length()}")

                    val rec = SherpaRecognizer()
                    val ok = rec.initialize(modelDir, vadPath)
                    writeDiag("SherpaRecognizer.initialize() result=$ok")
                    if (ok) {
                        sherpaRecognizer?.release()
                        sherpaRecognizer = rec
                        writeDiag("SenseVoice model loaded successfully")
                    } else {
                        writeDiag("FAILED to initialize SherpaRecognizer")
                        rec.release()
                    }
                } catch (e: Exception) {
                    writeDiag("loadModel EXCEPTION: ${e.message}\n${e.stackTraceToString()}")
                }
            }
        }

        override fun unloadModel() {
            sherpaRecognizer?.release()
            sherpaRecognizer = null
            Log.i(TAG, "Model unloaded")
        }

        override fun startRecognition(language: String, callback: IWhisperCallback?) {
            if (this@WhisperService.isRecognizing) {
                try { callback?.onError("Already recognizing") } catch (_: RemoteException) {}
                return
            }
            if (sherpaRecognizer?.isReady() != true) {
                startFallbackRecognition(language, callback)
                return
            }
            // Single-shot recognition: record until silence, then transcribe all at once
            this@WhisperService.isRecognizing = true
            recordingJob = scope.launch {
                try {
                    val audioData = recordAudioUntilSilence()
                    try { callback?.onPartialResult("Transcribing...") } catch (_: RemoteException) {}
                    val rec = sherpaRecognizer ?: return@launch
                    val results = rec.processAudio(audioData)
                    val flushed = rec.flush()
                    rec.reset()
                    val text = (results + flushed)
                        .filterNot { postProcessor.isHallucination(it) }
                        .joinToString("")
                    withContext(Dispatchers.Main) {
                        try { callback?.onResult(text) } catch (_: RemoteException) {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Recognition error", e)
                    withContext(Dispatchers.Main) {
                        try { callback?.onError(e.message ?: "Recognition failed") } catch (_: RemoteException) {}
                    }
                } finally {
                    this@WhisperService.isRecognizing = false
                }
            }
        }

        override fun stopRecognition() {
            writeDiag("stopRecognition: recordingJob=${recordingJob != null}, cb=${continuousCallback != null}")
            val job = recordingJob
            if (job != null) {
                val cb = continuousCallback
                val buf = textBuffer

                recordingJob = null
                continuousCallback = null
                this@WhisperService.isRecognizing = false

                scope.launch {
                    job.cancel()
                    job.join()

                    try {
                        val rec = sherpaRecognizer
                        if (rec != null) {
                            val flushed = rec.flush()
                            if (flushed.isNotEmpty()) {
                                buf.append(flushed.joinToString(""))
                            }
                            rec.reset()
                        }
                    } catch (e: Exception) {
                        writeDiag("stopRecognition flush EXCEPTION: ${e.message}")
                    }

                    val localFinal = buf.toString()
                    // Cloud final pass (opt-in): replace the on-device transcription
                    // with a high-accuracy cloud result; fall back to local on any error.
                    val finalText = maybeCloudFinal(localFinal)
                    writeDiag("stopRecognition: finalText='${finalText.take(100)}' (${finalText.length} chars)")
                    try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
                    try {
                        cb?.onResult(finalText)
                    } catch (e: RemoteException) {
                        writeDiag("stopRecognition: onResult IPC FAILED, text LOST: '${finalText.take(80)}'")
                    }
                }
                return
            }
            recordingJob?.cancel()
            this@WhisperService.isRecognizing = false
        }

        override fun startContinuousRecognition(language: String, callback: IWhisperCallback) {
            writeDiag("startContinuousRecognition: isRecognizing=$isRecognizing, sherpaReady=${sherpaRecognizer?.isReady()}")
            if (this@WhisperService.isRecognizing) {
                writeDiag("startContinuousRecognition: REJECTED, already recognizing")
                try { callback.onError("Already recognizing") } catch (_: RemoteException) {}
                return
            }
            if (sherpaRecognizer?.isReady() != true) {
                writeDiag("startContinuousRecognition: REJECTED, model not loaded")
                try { callback.onError("Model not loaded") } catch (_: RemoteException) {}
                return
            }

            // Start foreground service for microphone access on Android 12+
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val channelId = "whisper_recording"
                    val channel = android.app.NotificationChannel(
                        channelId, "Voice Input", android.app.NotificationManager.IMPORTANCE_LOW
                    )
                    val nm = getSystemService(android.app.NotificationManager::class.java)
                    nm.createNotificationChannel(channel)
                    val notification = android.app.Notification.Builder(this@WhisperService, channelId)
                        .setContentTitle("Nacre")
                        .setContentText("Listening...")
                        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                        .build()
                    startForeground(9999, notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                }
            } catch (e: Exception) {
                writeDiag("startContinuousRecognition: foreground service FAILED: ${e.message}")
            }

            this@WhisperService.isRecognizing = true
            continuousCallback = callback
            textBuffer = StringBuilder()
            cloudAsrActive = space.manus.nacre.ai.cloud.CloudAsrConfig.isEnabled(this@WhisperService.applicationContext)
            synchronized(cloudBuffer) { cloudBuffer.clear() }
            if (cloudAsrActive) writeDiag("cloud ASR active for this utterance")

            recordingJob = scope.launch(Dispatchers.Default) {
                val bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT
                )
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT,
                    bufferSize * 2
                )
                recorder.startRecording()
                writeDiag("AudioRecord state=${recorder.state}, recordingState=${recorder.recordingState}")

                // Read 512 samples per iteration (matches Silero VAD window size)
                val readBuffer = FloatArray(VAD_WINDOW_SIZE)
                val maxReads = SAMPLE_RATE * CONTINUOUS_MAX_DURATION_SEC / VAD_WINDOW_SIZE
                var totalReads = 0
                val rec = sherpaRecognizer ?: run {
                    writeDiag("ERROR: sherpaRecognizer is null in recording loop")
                    return@launch
                }
                writeDiag("Recording started, sherpaRecognizer ready=${rec.isReady()}")

                try {
                    while (isActive && totalReads < maxReads) {
                        val read = recorder.read(readBuffer, 0, VAD_WINDOW_SIZE, AudioRecord.READ_BLOCKING)
                        if (read <= 0) continue
                        totalReads++

                        val samples = if (read == VAD_WINDOW_SIZE) readBuffer else readBuffer.copyOf(read)
                        if (cloudAsrActive) {
                            // Keep a copy of the raw audio for the cloud final pass.
                            val copy = readBuffer.copyOf(read)
                            synchronized(cloudBuffer) { cloudBuffer.add(copy) }
                        }
                        val segments = rec.processAudio(samples)

                        if (segments.isNotEmpty()) {
                            var appendedAny = false
                            for (text in segments) {
                                if (postProcessor.isHallucination(text)) {
                                    writeDiag("Segment DISCARDED as hallucination: '${text.take(60)}'")
                                    continue
                                }
                                textBuffer.append(text)
                                appendedAny = true
                            }
                            if (appendedAny) {
                                writeDiag("Segment detected: '${textBuffer}' (totalReads=$totalReads)")
                                try {
                                    continuousCallback?.onPartialResult(textBuffer.toString())
                                } catch (_: RemoteException) {
                                    Log.w(TAG, "Partial result callback failed, stopping")
                                    break
                                }
                            }
                        }
                    }
                } finally {
                    recorder.stop()
                    recorder.release()
                }
            }
        }

        override fun cancelContinuousRecognition() {
            stopContinuousRecordingInternal()
            textBuffer.clear()
            cloudAsrActive = false
            synchronized(cloudBuffer) { cloudBuffer.clear() }
        }
    }

    /**
     * If cloud ASR is active, transcribe the buffered utterance via the cloud and
     * return that text; otherwise (or on any failure / empty audio) return
     * [localFinal]. Runs on the caller's worker coroutine. Consumes the buffer.
     */
    private fun maybeCloudFinal(localFinal: String): String {
        if (!cloudAsrActive) return localFinal
        val chunks = synchronized(cloudBuffer) { ArrayList(cloudBuffer) }
        cloudAsrActive = false
        synchronized(cloudBuffer) { cloudBuffer.clear() }
        if (chunks.isEmpty()) return localFinal
        val total = chunks.sumOf { it.size }
        val flat = FloatArray(total)
        var off = 0
        for (c in chunks) {
            System.arraycopy(c, 0, flat, off, c.size)
            off += c.size
        }
        val cloud = space.manus.nacre.ai.cloud.CloudAsrClient.transcribe(applicationContext, flat, SAMPLE_RATE)
        return if (!cloud.isNullOrBlank()) {
            writeDiag("cloud ASR final used (${flat.size / SAMPLE_RATE}s audio, ${cloud.length} chars)")
            cloud
        } else {
            writeDiag("cloud ASR returned null; using local final")
            localFinal
        }
    }

    /**
     * Record audio until silence detected (for single-shot mode).
     */
    private fun recordAudioUntilSilence(): FloatArray {
        val maxDurationSec = 30
        val silenceThresholdSec = 5
        val silenceRmsThreshold = 0.005f
        val chunkSamples = SAMPLE_RATE / 4 // 250ms chunks

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
            ),
            SAMPLE_RATE * maxDurationSec * 4
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, bufferSize
        )

        val samples = FloatArray(SAMPLE_RATE * maxDurationSec)
        var offset = 0
        var silentChunks = 0
        val maxSilentChunks = silenceThresholdSec * 4
        var hasVoice = false

        try {
            recorder.startRecording()
            while (offset < samples.size && isRecognizing) {
                val toRead = minOf(chunkSamples, samples.size - offset)
                val read = recorder.read(samples, offset, toRead, AudioRecord.READ_BLOCKING)
                if (read <= 0) break
                offset += read

                var sumSq = 0f
                for (i in (offset - read) until offset) { sumSq += samples[i] * samples[i] }
                val rms = kotlin.math.sqrt(sumSq / read)

                if (rms < silenceRmsThreshold) {
                    silentChunks++
                    if (hasVoice && silentChunks >= maxSilentChunks) break
                } else {
                    hasVoice = true
                    silentChunks = 0
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
        return if (offset < samples.size) samples.copyOf(offset) else samples
    }

    private fun stopContinuousRecordingInternal() {
        recordingJob?.cancel()
        recordingJob = null
        isRecognizing = false
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "WhisperService created (process=${android.os.Process.myPid()})")
    }

    private fun pickSenseVoiceModelFile(modelDir: String): java.io.File? {
        return listOf("model.onnx", "model.int8.onnx")
            .map { java.io.File(modelDir, it) }
            .firstOrNull { it.exists() && it.length() > 0 }
    }

    /**
     * Fallback to Android SpeechRecognizer when SenseVoice model is unavailable.
     */
    private fun startFallbackRecognition(language: String, callback: IWhisperCallback?) {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            try { callback?.onError("Speech recognition not available") } catch (_: RemoteException) {}
            return
        }
        isRecognizing = true
        scope.launch(Dispatchers.Main) {
            try {
                val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this@WhisperService)
                recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
                    override fun onResults(results: android.os.Bundle?) {
                        isRecognizing = false
                        val text = results?.getStringArrayList(
                            android.speech.SpeechRecognizer.RESULTS_RECOGNITION
                        )?.firstOrNull() ?: ""
                        try { callback?.onResult(text) } catch (_: RemoteException) {}
                        recognizer.destroy()
                    }
                    override fun onPartialResults(partialResults: android.os.Bundle?) {
                        val text = partialResults?.getStringArrayList(
                            android.speech.SpeechRecognizer.RESULTS_RECOGNITION
                        )?.firstOrNull() ?: return
                        try { callback?.onPartialResult(text) } catch (_: RemoteException) {}
                    }
                    override fun onError(error: Int) {
                        isRecognizing = false
                        try { callback?.onError("SpeechRecognizer error: $error") } catch (_: RemoteException) {}
                        recognizer.destroy()
                    }
                    override fun onReadyForSpeech(params: android.os.Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                })
                val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(android.speech.RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                }
                recognizer.startListening(intent)
            } catch (e: Exception) {
                isRecognizing = false
                try { callback?.onError(e.message ?: "Fallback recognition failed") } catch (_: RemoteException) {}
            }
        }
    }

    override fun onDestroy() {
        recordingJob?.cancel()
        stopContinuousRecordingInternal()
        sherpaRecognizer?.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun writeDiag(message: String) {
        try {
            val file = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                "nacre-whisper-debug.txt"
            )
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            file.appendText("[$ts] $message\n")
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "WhisperService"
        private const val SAMPLE_RATE = 16000
        private const val CONTINUOUS_MAX_DURATION_SEC = 360
        private const val VAD_WINDOW_SIZE = 512 // Silero VAD window size
    }
}
