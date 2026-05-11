package space.manus.nacre.ime.input

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import space.manus.nacre.ai.ILlmCallback
import space.manus.nacre.ai.ILlmService
import space.manus.nacre.ai.IWhisperService
import space.manus.nacre.ai.IWhisperCallback
import space.manus.nacre.ai.LlmPostProcessor
import space.manus.nacre.ime.NacreInputMethodService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Typeless-level voice input manager.
 *
 * Key differences from basic SpeechRecognizer usage:
 * - Zero-gap continuous recognition: next recognizer pre-created before current finishes
 * - Streaming partial commit: long partial results committed incrementally
 * - Intelligent utterance merging: avoids fragmented sentences
 * - All recoverable errors auto-restart with zero delay
 * - Offline-preferred with network fallback
 * - Smart punctuation with context awareness
 */
class VoiceInputManager(private val service: NacreInputMethodService) {

    var isListening by mutableStateOf(false)
        private set
    var partialText by mutableStateOf("")
        private set
    var lastError by mutableStateOf("")
        private set
    /** Normalized RMS level 0f..1f for visual feedback */
    var rmsLevel by mutableFloatStateOf(0f)
        private set

    private var speechRecognizer: SpeechRecognizer? = null
    private var nextRecognizer: SpeechRecognizer? = null // Pre-created for zero-gap restart
    private var continuousMode = false
    private var currentLanguage = "ja-JP"
    private var committedInSession = StringBuilder()
    private var utteranceCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    // Streaming partial commit tracking
    private var lastCommittedPartial = ""
    private var partialStableCount = 0
    private var lastPartialText = ""
    private var lastPartialTime = 0L

    // Consecutive error tracking for backoff
    private var consecutiveErrors = 0

    // Whisper continuous mode
    @Volatile private var whisperService: IWhisperService? = null
    @Volatile private var whisperBound = false
    @Volatile private var isWhisperContinuousMode = false
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    // LLM post-processing (local Gemma/Qwen via isolated LlmService over AIDL)
    @Volatile private var llmService: ILlmService? = null
    @Volatile private var llmBound = false

    private val whisperConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
            val svc = IWhisperService.Stub.asInterface(binder)
            whisperBound = true
            writeDiagnostic("onServiceConnected: WhisperService bound")
            Thread {
                try {
                    if (!svc.isModelLoaded) {
                        val downloader = space.manus.nacre.ai.ModelDownloader(service)
                        val modelDir = downloader.getSenseVoiceModelDir()
                        val vadPath = downloader.getVadModelPath()
                        writeDiagnostic("onServiceConnected: modelDir=$modelDir, vadPath=$vadPath")
                        if (modelDir != null && vadPath != null) {
                            svc.loadModel("$modelDir|$vadPath")
                            for (i in 0 until 120) {
                                Thread.sleep(500)
                                if (svc.isModelLoaded) break
                            }
                            if (!svc.isModelLoaded) {
                                writeDiagnostic("onServiceConnected: model load TIMEOUT after 60s")
                            }
                        } else {
                            writeDiagnostic("onServiceConnected: model NOT FOUND (dir=$modelDir, vad=$vadPath)")
                        }
                    } else {
                        writeDiagnostic("onServiceConnected: model already loaded")
                    }
                    if (svc.isModelLoaded) {
                        whisperService = svc
                        writeDiagnostic("onServiceConnected: SUCCESS, whisperService set")
                    } else {
                        writeDiagnostic("onServiceConnected: FAIL, model not loaded, fallback to SpeechRecognizer")
                    }
                } catch (e: Exception) {
                    writeDiagnostic("onServiceConnected EXCEPTION: ${e.message}")
                }
            }.start()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            Log.w(TAG, "WhisperService disconnected unexpectedly")
            whisperService = null
            whisperBound = false
            if (isWhisperContinuousMode) {
                isWhisperContinuousMode = false
                // Clear composing text left from the dead Whisper session
                mainHandler.post {
                    service.currentInputConnection?.finishComposingText()
                    partialText = ""
                    rmsLevel = 0f
                    releaseAudioFocus()
                    // Fallback to SpeechRecognizer if still listening
                    if (isListening) {
                        Log.i(TAG, "Falling back to SpeechRecognizer after Whisper disconnect")
                        startRecognizer()
                    }
                }
            }
        }
    }

    private val llmConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) {
            val svc = ILlmService.Stub.asInterface(binder)
            llmBound = true
            writeDiagnostic("llmConnection.onServiceConnected")
            Thread {
                try {
                    if (!svc.isModelLoaded) {
                        val downloader = space.manus.nacre.ai.ModelDownloader(service)
                        // Search all standard locations (filesDir, external files, /sdcard/Download, MediaStore).
                        // Gemma 4 is the default; Qwen is still accepted as a legacy fallback.
                        val modelPaths = downloader.getPreferredLlmModelPaths()
                        if (modelPaths.isNotEmpty()) {
                            var loadedPath: String? = null
                            for (modelPath in modelPaths) {
                                if (tryLoadLlmModel(svc, modelPath)) {
                                    loadedPath = modelPath
                                    break
                                }
                                try {
                                    svc.unloadModel()
                                } catch (_: Exception) {
                                }
                            }
                            if (loadedPath == null) {
                                writeDiagnostic("llmConnection: no local LLM could be loaded — refinement disabled for this session")
                                return@Thread
                            }
                            llmService = svc
                            val modelFile = java.io.File(loadedPath)
                            writeDiagnostic("llmConnection: model ready: ${modelFile.name} (${modelFile.length() / 1024 / 1024}MB)")
                        } else {
                            downloader.ensureDefaultModelsDownloaded(downloadCompactKenLm = false)
                            writeDiagnostic("llmConnection: LLM model not found — Gemma 4 download requested, refinement disabled for this session")
                            return@Thread
                        }
                    } else {
                        llmService = svc
                        writeDiagnostic("llmConnection: model already loaded")
                    }
                } catch (e: Exception) {
                    writeDiagnostic("llmConnection EXCEPTION: ${e.message}")
                }
            }.start()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            Log.w(TAG, "LlmService disconnected")
            llmService = null
            llmBound = false
        }
    }

    private fun tryLoadLlmModel(svc: ILlmService, modelPath: String): Boolean {
        val modelFile = java.io.File(modelPath)
        writeDiagnostic("llmConnection: loading model ${modelFile.absolutePath} (${modelFile.length() / 1024 / 1024}MB)")
        svc.loadModel(modelFile.absolutePath)

        // Local GGUF models typically mmap in 30–90s on mid-range Android; allow
        // 3 min per candidate before falling back.
        val start = System.currentTimeMillis()
        while (!svc.isModelLoaded && System.currentTimeMillis() - start < 180_000) {
            Thread.sleep(500)
        }
        val elapsed = (System.currentTimeMillis() - start) / 1000
        if (svc.isModelLoaded) {
            writeDiagnostic("llmConnection: ${modelFile.name} ready after ${elapsed}s")
            return true
        }
        writeDiagnostic("llmConnection: ${modelFile.name} did not become ready after ${elapsed}s")
        return false
    }

    private val whisperCallback = object : IWhisperCallback.Stub() {
        override fun onResult(text: String) {
            writeDiagnostic("whisperCallback.onResult: text='${text.take(80)}' (${text.length} chars)")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                isWhisperContinuousMode = false
                isListening = false
                partialText = ""
                rmsLevel = 0f
                releaseAudioFocus()

                if (text.isNotBlank()) {
                    val cleaned = space.manus.nacre.ai.LlmPostProcessor.quickClean(text)
                    writeDiagnostic("whisperCallback.onResult: quickClean='${cleaned.take(80)}'")
                    service.currentInputConnection?.commitText(cleaned, 1)
                    // Stage 2: LLM refinement in background, replace quickClean result if better
                    tryLlmRefinement(cleaned)
                } else {
                    writeDiagnostic("whisperCallback.onResult: text is BLANK, skipping")
                }
            }
        }

        override fun onPartialResult(text: String) {
            // Typeless-style: no preview during recording.
        }

        override fun onError(message: String) {
            writeDiagnostic("whisperCallback.onError: $message")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Log.w(TAG, "Whisper error: $message")
                isWhisperContinuousMode = false
                isListening = false
                partialText = ""
                rmsLevel = 0f
                lastError = "Whisper: $message"
                releaseAudioFocus()
                // Don't auto-fallback to SpeechRecognizer — let user see the error and retry
            }
        }
    }

    // Deferred commit: hold results during pauses instead of committing immediately.
    // This prevents text from being fragmented when the user pauses to think.
    // Text is committed after DEFERRED_COMMIT_DELAY_MS of no new speech,
    // or immediately when the user taps stop.
    private var deferredText = StringBuilder()
    private var deferredCommitRunnable: Runnable? = null

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(service)
    }

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            service, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start continuous voice input. Keeps listening until explicitly stopped.
     */
    fun startListening(language: String = "ja-JP") {
        if (isListening) return
        if (service.inputEngine.isPasswordField) {
            Log.w(TAG, "Voice input disabled in password field")
            return
        }
        if (!isBatteryOk()) {
            Log.w(TAG, "Battery too low for voice input")
            lastError = "バッテリー残量不足"
            return
        }
        if (!hasPermission()) {
            Log.w(TAG, "RECORD_AUDIO permission not granted, launching permission request")
            lastError = "マイク権限を許可してください"
            try {
                val intent = Intent().apply {
                    setClassName(service.packageName, "${service.packageName}.PermissionActivity")
                    putExtra("permission", Manifest.permission.RECORD_AUDIO)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch permission activity", e)
            }
            return
        }
        if (whisperService == null && !isAvailable()) {
            Log.w(TAG, "Speech recognition not available (no Whisper, no SpeechRecognizer)")
            lastError = "音声認識が利用できません"
            return
        }

        isListening = true
        partialText = ""
        lastError = ""
        rmsLevel = 0f
        continuousMode = true
        currentLanguage = language
        committedInSession.clear()
        utteranceCount = 0
        consecutiveErrors = 0
        lastCommittedPartial = ""
        partialStableCount = 0
        lastPartialText = ""
        lastPartialTime = 0L
        deferredText.clear()
        cancelDeferredCommit()

        // Whisper priority — use continuous mode if model loaded
        Log.i(TAG, "startListening: whisperService=${whisperService != null}, whisperBound=$whisperBound, isWhisperContinuousMode=$isWhisperContinuousMode")
        writeDiagnostic("startListening: whisperService=${whisperService != null}, whisperBound=$whisperBound")
        if (whisperService == null && whisperBound) {
            Log.w(TAG, "startListening: WhisperService is bound but whisperService proxy is null — model likely not loaded")
            writeDiagnostic("WARNING: bound but proxy null — model not loaded")
        }
        if (whisperService != null) {
            try {
                val modelLoaded = whisperService!!.isModelLoaded
                Log.i(TAG, "startListening: Whisper modelLoaded=$modelLoaded")
                if (modelLoaded) {
                    requestAudioFocus()
                    whisperService!!.startContinuousRecognition("auto", whisperCallback)
                    isWhisperContinuousMode = true  // Set AFTER successful IPC
                    Log.i(TAG, "startListening: Whisper continuous mode STARTED")
                    writeDiagnostic("WHISPER STARTED: continuous mode active")
                    return
                }
            } catch (e: android.os.RemoteException) {
                Log.w(TAG, "Whisper IPC failed, falling back to SpeechRecognizer", e)
                isWhisperContinuousMode = false
                releaseAudioFocus()
            }
        }

        startRecognizer()
    }

    private fun createRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
            // Accept both ja and en for bilingual users
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, currentLanguage)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, true)
            // Offline preference disabled — causes ERROR_NO_MATCH on devices
            // without offline model downloaded. Let the engine decide.
            // putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Silence tolerance — maximized for always-on dictation
            putExtra("android.speech.extra.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS", 10000L)
            putExtra("android.speech.extra.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS", 5000L)
            putExtra("android.speech.extra.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS", 500L)
            // Web search bias off (better for natural language)
            putExtra(RecognizerIntent.EXTRA_WEB_SEARCH_ONLY, false)
        }
    }

    private fun startRecognizer() {
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null

                // Use pre-created recognizer if available (zero-gap restart)
                val recognizer = nextRecognizer ?: SpeechRecognizer.createSpeechRecognizer(service.applicationContext)
                nextRecognizer = null

                if (recognizer == null) {
                    Log.e(TAG, "createSpeechRecognizer returned null")
                    lastError = "音声認識を初期化できません"
                    isListening = false
                    continuousMode = false
                    return@post
                }

                recognizer.setRecognitionListener(listener)
                speechRecognizer = recognizer
                recognizer.startListening(createRecognizerIntent())
                Log.i(TAG, "SpeechRecognizer started (lang=$currentLanguage, utterance=$utteranceCount)")
                writeDiagnostic("FALLBACK: SpeechRecognizer started (NOT Whisper)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening", e)
                lastError = "音声認識の開始に失敗: ${e.message}"
                isListening = false
                continuousMode = false
            }
        }
    }

    /**
     * Pre-create the next recognizer while current one is still running.
     * This eliminates the gap between utterances.
     */
    private fun prepareNextRecognizer() {
        if (!continuousMode) return
        mainHandler.post {
            try {
                if (nextRecognizer == null) {
                    nextRecognizer = SpeechRecognizer.createSpeechRecognizer(service.applicationContext)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to pre-create next recognizer", e)
            }
        }
    }

    fun stopListening() {
        writeDiagnostic("stopListening: whisperMode=$isWhisperContinuousMode, whisperService=${whisperService != null}")
        if (isWhisperContinuousMode) {
            isWhisperContinuousMode = false
            isListening = false
            partialText = ""
            rmsLevel = 0f
            try {
                whisperService?.stopRecognition()
            } catch (e: android.os.RemoteException) {
                writeDiagnostic("stopListening: IPC FAILED: ${e.message}")
                service.currentInputConnection?.finishComposingText()
            }
            // Release audio focus AFTER stopRecognition IPC — recording may still be
            // finishing in the remote process; releasing focus early could inject noise.
            releaseAudioFocus()
            return
        }
        continuousMode = false
        // Commit any remaining partial text
        commitRemainingPartial()
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
        try {
            nextRecognizer?.destroy()
            nextRecognizer = null
        } catch (_: Exception) {}
        isListening = false
        partialText = ""
        rmsLevel = 0f
    }

    fun cancel() {
        if (isWhisperContinuousMode) {
            try {
                whisperService?.cancelContinuousRecognition()
            } catch (e: android.os.RemoteException) {
                Log.w(TAG, "Whisper cancelContinuousRecognition IPC failed", e)
            }
            isWhisperContinuousMode = false
            isListening = false
            partialText = ""
            rmsLevel = 0f
            releaseAudioFocus()
            service.currentInputConnection?.finishComposingText()
            return
        }
        continuousMode = false
        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {}
        try {
            nextRecognizer?.destroy()
            nextRecognizer = null
        } catch (_: Exception) {}
        isListening = false
        partialText = ""
        rmsLevel = 0f
    }

    fun release() {
        cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    // ── Streaming partial commit ────────────────────────────────────────

    /**
     * Typeless-style: commit stable portions of partial results in real-time.
     * If the same partial text appears 3+ times in a row (stable for ~750ms),
     * commit the stable prefix and keep only the changing suffix as partial.
     */
    private fun tryStreamingCommit(partial: String) {
        if (partial.isEmpty()) return
        val now = System.currentTimeMillis()

        if (partial == lastPartialText) {
            partialStableCount++
        } else {
            partialStableCount = 0
            lastPartialText = partial
            lastPartialTime = now
        }

        // Commit stable prefix after 3 consecutive identical partials (~750ms)
        // or if partial is very long (>30 chars) and hasn't changed for 500ms
        val shouldCommit = (partialStableCount >= 3) ||
            (partial.length > 30 && now - lastPartialTime > 500 && partialStableCount >= 1)

        if (shouldCommit && partial.length > lastCommittedPartial.length) {
            // Find stable prefix: everything except the last "word" being edited
            val commitUpTo = findStablePrefix(partial)
            if (commitUpTo > lastCommittedPartial.length) {
                val toCommit = partial.substring(lastCommittedPartial.length, commitUpTo)
                if (toCommit.isNotBlank()) {
                    val converted = convertVoiceCommands(toCommit)
                    val spaced = addUtteranceSpacing(converted)
                    service.currentInputConnection?.commitText(spaced, 1)
                    committedInSession.append(spaced)
                    lastCommittedPartial = partial.substring(0, commitUpTo)
                    // Log.d removed: never log user voice input text
                }
            }
        }
    }

    /**
     * Find the boundary of the stable prefix in partial text.
     * For Japanese: commit up to the last particle/punctuation boundary.
     * For English: commit up to the last space (complete words only).
     *
     * When KenLM is available, verify the chosen boundary by scoring:
     * if the prefix forms a natural sentence fragment, commit it.
     * If not, try earlier boundaries.
     */
    private fun findStablePrefix(text: String): Int {
        if (text.length < 3) return 0
        val hasJapanese = text.any { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF }

        if (hasJapanese) {
            val punctuationIdx = text.indexOfLast { it in "。、！？!?,、" }
            if (punctuationIdx >= 0 && punctuationIdx >= (text.length * 0.4f).toInt()) {
                return punctuationIdx + 1
            }

            // Collect candidate break points (particle/punctuation boundaries)
            val target = (text.length * 0.8).toInt()
            val candidates = mutableListOf<Int>()
            for (i in target downTo (text.length / 2)) {
                val c = text[i]
                if (c in "。、！？「」（）") {
                    candidates.add(i + 1)
                } else if (i > 0 && c in "はがをにでとのもへやかなけど") {
                    candidates.add(i + 1)
                }
            }

            // If no natural break points, fall back to target position
            if (candidates.isEmpty()) return target

            // With KenLM: pick the boundary that creates the highest-scoring prefix
            val scorer = (service.inputEngine.dictionary as? NacreDictionary)?.kenLmScorer
            if (scorer != null && scorer.isReady() && candidates.size > 1) {
                val context = committedInSession.toString().takeLast(20)
                val prefixes = candidates.take(4).map { listOf(text.substring(0, it)) }
                val scores = scorer.scoreBatch(prefixes, context)
                val bestIdx = scores.indices.maxByOrNull { scores[it] } ?: 0
                return candidates[bestIdx]
            }

            return candidates.first()
        } else {
            // English: commit complete words only
            val lastSpace = text.lastIndexOf(' ', text.length - 2)
            return if (lastSpace > lastCommittedPartial.length) lastSpace + 1 else 0
        }
    }

    /**
     * When stopping, commit any remaining uncommitted partial text.
     */
    // ── Deferred commit ─────────────────────────────────────────────────
    // Instead of committing each utterance immediately, buffer results and
    // commit after a pause. This lets the user think mid-sentence without
    // the text being fragmented. The deferred text is shown as partialText
    // (preview) until committed.

    private fun cancelDeferredCommit() {
        deferredCommitRunnable?.let { mainHandler.removeCallbacks(it) }
        deferredCommitRunnable = null
    }

    /**
     * Flush all buffered text to the input field.
     * Only called on explicit stop — never on a timer.
     * This is the Typeless model: record forever, commit on user action.
     */
    private fun flushDeferredText() {
        cancelDeferredCommit()
        val text = deferredText.toString()
        deferredText.clear()
        if (text.isBlank()) return

        val withCommas = insertMidSentenceCommas(text)
        val processed = smartPunctuation(withCommas)
        service.currentInputConnection?.commitText(processed, 1)
        committedInSession.append(processed)
        utteranceCount++

        partialText = ""
    }

    private fun appendDeferredText(text: String) {
        if (text.isEmpty()) return
        val converted = convertVoiceCommands(text)
        if (deferredText.isNotEmpty()) {
            // Japanese: no space needed between utterances
            val lastChar = deferredText.last()
            val hasJapanese = lastChar.code in 0x3000..0x9FFF || lastChar.code in 0xFF00..0xFFEF
            if (!hasJapanese && converted.first().code in 0x20..0x7E) {
                deferredText.append(" ")
            }
        }
        deferredText.append(converted)
        // Show buffered text as live preview in candidate bar
        partialText = deferredText.toString()
        // No timer — text stays in buffer until user taps stop
    }

    private fun commitRemainingPartial() {
        val remaining = if (lastCommittedPartial.isNotEmpty() && lastPartialText.length > lastCommittedPartial.length) {
            lastPartialText.substring(lastCommittedPartial.length)
        } else if (lastCommittedPartial.isEmpty() && partialText.isNotEmpty()) {
            partialText
        } else {
            null
        }
        if (remaining != null && remaining.isNotBlank()) {
            val converted = convertVoiceCommands(remaining)
            val spaced = addUtteranceSpacing(converted)
            val processed = smartPunctuation(spaced)
            service.currentInputConnection?.commitText(processed, 1)
            committedInSession.append(processed)
        }
        lastCommittedPartial = ""
        partialStableCount = 0
        lastPartialText = ""
    }

    // ── Voice commands → symbol conversion ──────────────────────────

    /**
     * 音声コマンドを記号に変換する。Typeless同等。
     * 「てん」→、 「まる」→。 「はてな」→？ 等。
     * テキスト中のどこに出現しても変換する（文末以外も）。
     */
    private fun convertVoiceCommands(text: String): String {
        var result = text

        // 完全一致のコマンド（単独で発話された場合）
        val exactCommands = mapOf(
            "改行" to "\n",
            "かいぎょう" to "\n",
            "エンター" to "\n",
            "スペース" to " ",
            "タブ" to "\t",
        )
        val trimmed = result.trim()
        exactCommands[trimmed]?.let { return it }

        // インライン変換（文中に出現するコマンド）
        // 順番重要: 長いパターンを先にマッチ
        val inlineCommands = listOf(
            // 句読点
            "まる" to "。",
            "マル" to "。",
            "てん" to "、",
            "テン" to "、",
            "こめてん" to "※",
            "コメテン" to "※",
            // 疑問符・感嘆符
            "はてな" to "？",
            "ハテナ" to "？",
            "クエスチョン" to "？",
            "くえすちょん" to "？",
            "クエスチョンマーク" to "？",
            "びっくり" to "！",
            "ビックリ" to "！",
            "びっくりマーク" to "！",
            "ビックリマーク" to "！",
            "エクスクラメーション" to "！",
            "かんたんふ" to "！",
            "感嘆符" to "！",
            "ぎもんふ" to "？",
            "疑問符" to "？",
            // 括弧
            "かっこ" to "「",
            "カッコ" to "「",
            "かっことじ" to "」",
            "カッコトジ" to "」",
            "とじかっこ" to "」",
            "まるかっこ" to "（",
            "まるかっことじ" to "）",
            "かぎかっこ" to "「",
            "かぎかっことじ" to "」",
            "にじゅうかっこ" to "『",
            "にじゅうかっことじ" to "』",
            // その他記号
            "なかぐろ" to "・",
            "ナカグロ" to "・",
            "中黒" to "・",
            "コロン" to "：",
            "ころん" to "：",
            "セミコロン" to "；",
            "せみころん" to "；",
            "スラッシュ" to "／",
            "すらっしゅ" to "／",
            "さんてん" to "…",
            "三点" to "…",
            "てんてんてん" to "…",
            "テンテンテン" to "…",
            "ハイフン" to "ー",
            "はいふん" to "ー",
            "マイナス" to "−",
            "まいなす" to "−",
            "プラス" to "＋",
            "ぷらす" to "＋",
            "イコール" to "＝",
            "いこーる" to "＝",
            "アンダーバー" to "＿",
            "あんだーばー" to "＿",
            "アットマーク" to "＠",
            "あっとまーく" to "＠",
            "シャープ" to "＃",
            "しゃーぷ" to "＃",
            "アスタリスク" to "＊",
            "あすたりすく" to "＊",
            "パーセント" to "％",
            "ぱーせんと" to "％",
            "アンパサンド" to "＆",
            "あんぱさんど" to "＆",
            "ドル" to "＄",
            "どる" to "＄",
            "えん" to "￥",
            "円マーク" to "￥",
            "チルダ" to "～",
            "ちるだ" to "～",
            "バックスラッシュ" to "＼",
            "ばっくすらっしゅ" to "＼",
            "パイプ" to "｜",
            "ぱいぷ" to "｜",
            // スペース・改行（文中）
            "かいぎょう" to "\n",
            "改行" to "\n",
        )

        // Words that contain command substrings but should NOT be converted
        // e.g. "かっこいい" contains "かっこ" but should not become "「いい"
        val safeWords = setOf(
            "かっこいい", "カッコイイ", "かっこ悪い", "カッコ悪い",
            "かっこう", "カッコウ", // 郭公
            "てんき", "テンキ", "てんきん", "テンキン", // 天気, 転勤
            "てんけん", "テンケン", // 点検
            "てんさい", "テンサイ", // 天才
            "まるで", "マルデ", // まるで〜
            "まるい", "マルイ", // 丸い / マルイ
            "まるごと", "マルゴト",
        )

        // Check if any safe word is present, temporarily replace them
        var protected = result
        val placeholders = mutableMapOf<String, String>()
        for (word in safeWords) {
            if (protected.contains(word)) {
                val placeholder = "\u0000${placeholders.size}\u0000"
                placeholders[placeholder] = word
                protected = protected.replace(word, placeholder)
            }
        }

        // Apply inline command replacements
        for ((cmd, sym) in inlineCommands) {
            protected = protected.replace(cmd, sym)
        }

        // Restore protected words
        var final = protected
        for ((placeholder, word) in placeholders) {
            final = final.replace(placeholder, word)
        }

        return final
    }

    // ── Smart punctuation ─────────────────────────────────────────────

    /**
     * 文末に自然な句読点を自動追加する。
     * convertVoiceCommands() で明示的に入力された句読点がある場合はスキップ。
     * KenLMが利用可能な場合、パターンマッチに該当しない境界ケースで
     * 「？」「！」「。」のスコアを比較して最適な句読点を選択する。
     */
    private fun smartPunctuation(text: String): String {
        if (text.isEmpty()) return text
        val lastChar = text.last()
        // 既に句読点・記号で終わっている場合はそのまま
        if (lastChar in "。、！？.!?,;:「」『』（）・…\n") return text

        val hasJapanese = text.any { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF }

        if (hasJapanese) {
            // 疑問パターン（80+ patterns for comprehensive coverage）
            val questionEndings = arrayOf(
                // 丁寧語
                "ですか", "ますか", "ませんか", "でしょうか", "でしたか",
                "ございますか", "いただけますか", "くださいますか",
                // 確認系
                "よね", "だよね", "ですよね", "じゃない", "じゃないですか",
                "でしたっけ", "だっけ", "ではないですか", "ではありませんか",
                // カジュアル
                "かな", "かね", "かしら", "だろうか", "だろう", "でしょう",
                "じゃん", "なの", "のか",
                // 疑問詞で始まるフレーズの末尾
                "って何", "ってなに", "ってなんですか", "とは", "ってどういうこと",
                "ってこと", "ということ", "の意味", "ってどうなった",
                // 動詞疑問
                "できる", "できますか", "してくれる", "してもらえる",
                "知ってる", "知っていますか", "分かる", "わかりますか",
                "ある", "ありますか", "いる", "いますか",
                // 比較・選択
                "どっち", "どちら", "どれ", "どこ", "どう", "いつ", "なぜ",
                "どうして", "なんで", "いくら", "いくつ", "どのくらい",
                "何人", "何日", "何時",
            )
            // Check longest matches first to avoid partial matches
            if (questionEndings.sortedByDescending { it.length }.any { text.endsWith(it) } ||
                (text.endsWith("か") && text.length > 2) ||
                (text.endsWith("の") && text.length > 4)
            ) {
                return "$text？"
            }
            // 感嘆パターン
            val exclamationEndings = arrayOf(
                // 命令・依頼
                "ください", "くれ", "しろ", "しなさい", "やれ", "やめろ", "やめて",
                "ないで", "するな", "するぞ", "してくれ", "やってくれ",
                "頑張れ", "気をつけて", "お願い",
                // 感情
                "すごい", "すげー", "やばい", "やべー", "最高", "最悪",
                "うれしい", "嬉しい", "楽しい", "悲しい", "つらい", "辛い",
                "びっくり", "信じられない", "ありえない",
                // 完了・成功
                "やった", "できた", "終わった", "勝った", "成功",
                // 肯定強調
                "だろ", "だぜ", "だぞ", "だよ", "ぞ", "よ", "ね",
                "まじ", "マジ", "本当",
                // 挨拶系
                "ありがとう", "おめでとう", "お疲れ", "いいね",
            )
            if (exclamationEndings.sortedByDescending { it.length }.any { text.endsWith(it) }) {
                return "$text！"
            }

            // KenLM-assisted punctuation for ambiguous cases
            val scorer = (service.inputEngine.dictionary as? NacreDictionary)?.kenLmScorer
            if (scorer != null && scorer.isReady()) {
                val context = committedInSession.toString().takeLast(20)
                val scores = scorer.scoreBatch(
                    listOf(listOf("$text。"), listOf("$text？"), listOf("$text！")),
                    context,
                )
                val bestIdx = scores.indices.maxByOrNull { scores[it] } ?: 0
                return when (bestIdx) {
                    1 -> "$text？"
                    2 -> "$text！"
                    else -> "$text。"
                }
            }

            return "$text。"
        } else {
            val lower = text.lowercase()
            if (lower.startsWith("what ") || lower.startsWith("how ") ||
                lower.startsWith("why ") || lower.startsWith("where ") ||
                lower.startsWith("when ") || lower.startsWith("who ") ||
                lower.startsWith("which ") || lower.startsWith("is ") ||
                lower.startsWith("are ") || lower.startsWith("do ") ||
                lower.startsWith("does ") || lower.startsWith("can ") ||
                lower.startsWith("could ") || lower.startsWith("would ") ||
                lower.startsWith("should ") || lower.startsWith("have ") ||
                lower.startsWith("has ") || lower.startsWith("will ") ||
                lower.startsWith("did ") || lower.endsWith("?") ||
                lower.endsWith(" right") || lower.endsWith(" huh")
            ) {
                return "$text?"
            }
            return "$text."
        }
    }

    /**
     * Insert commas (、) at natural clause boundaries within Japanese text.
     * Targets conjunctive particles and connectives that typically precede a pause.
     */
    private fun insertMidSentenceCommas(text: String): String {
        if (text.length < 8) return text // Too short for mid-sentence commas
        val hasJapanese = text.any { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF }
        if (!hasJapanese) return text

        var result = text
        // Clause-boundary patterns: insert 、 after these conjunctive forms
        // Only insert if not already followed by punctuation
        val clauseBreaks = listOf(
            "ので" to "ので、", "から" to "から、", "けど" to "けど、",
            "けれど" to "けれど、", "けれども" to "けれども、",
            "ですが" to "ですが、", "ますが" to "ますが、",
            "たら" to "たら、", "ても" to "ても、", "のに" to "のに、",
            "ながら" to "ながら、", "つつ" to "つつ、",
            "そして" to "そして、", "しかし" to "しかし、",
            "でも" to "でも、", "ただ" to "ただ、", "また" to "また、",
            "さらに" to "さらに、", "それから" to "それから、",
            "ところが" to "ところが、", "一方" to "一方、",
            "なので" to "なので、", "だから" to "だから、",
        )
        for ((pattern, replacement) in clauseBreaks) {
            // Only replace if pattern is followed by non-punctuation content
            val idx = result.indexOf(pattern)
            if (idx >= 0) {
                val afterIdx = idx + pattern.length
                if (afterIdx < result.length && result[afterIdx] !in "。、！？「」『』（）・…\n、") {
                    result = result.substring(0, afterIdx) + "、" + result.substring(afterIdx)
                }
            }
        }
        return result
    }

    private fun addUtteranceSpacing(text: String): String {
        if (committedInSession.isEmpty()) return text
        val lastCommitted = committedInSession.last()
        // Japanese: no space needed
        if (lastCommitted.code in 0x3000..0x9FFF || lastCommitted.code in 0xFF00..0xFFEF) {
            return text
        }
        return if (lastCommitted != ' ') " $text" else text
    }

    /**
     * Select the best recognition result using confidence scores + KenLM language model.
     * If KenLM is available, re-rank candidates by combining engine confidence with
     * language model probability. This catches common mis-recognitions like
     * "こんにちわ" vs "こんにちは" where the LM strongly prefers the correct form.
     */
    private fun selectBestResult(results: Bundle?): String {
        val alternatives = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return ""
        if (alternatives.isEmpty()) return ""
        if (alternatives.size == 1) return alternatives[0]

        val confidences = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
        val normalizedAlternatives = alternatives.map { space.manus.nacre.ai.LlmPostProcessor.quickClean(it).trim() }

        // Try KenLM re-ranking if available
        val scorer = (service.inputEngine.dictionary as? NacreDictionary)?.kenLmScorer
        if (scorer != null && scorer.isReady()) {
            val precedingContext = committedInSession.toString().takeLast(80)
            val sentences = normalizedAlternatives.mapIndexed { index, cleaned ->
                listOf(cleaned.ifEmpty { alternatives[index] })
            }
            val lmScores = scorer.scoreBatch(sentences, precedingContext)

            // Combined score: confidence × weight + LM score × weight
            // LM scores are log10 probabilities (negative), higher = better
            var bestIdx = 0
            var bestScore = Float.MIN_VALUE
            for (i in alternatives.indices) {
                val conf = if (confidences != null && i < confidences.size) confidences[i] else 0.5f
                val candidateText = normalizedAlternatives[i].ifEmpty { alternatives[i] }
                // Normalize confidence (0-1) to similar scale as LM scores
                // LM scores are typically -10 to -2 range
                val combined = conf * 5f + lmScores[i] * 0.3f + if (candidateText.length < alternatives[i].length) 0.15f else 0f
                if (combined > bestScore) {
                    bestScore = combined
                    bestIdx = i
                }
            }
            return normalizedAlternatives[bestIdx].ifEmpty { alternatives[bestIdx] }
        }

        // Fallback: use confidence scores only
        if (confidences != null && confidences.size == alternatives.size) {
            var bestIdx = 0
            var bestConf = confidences[0]
            for (i in 1 until confidences.size) {
                if (confidences[i] > bestConf) {
                    bestConf = confidences[i]
                    bestIdx = i
                }
            }
            return normalizedAlternatives[bestIdx].ifEmpty { alternatives[bestIdx] }
        }
        return normalizedAlternatives[0].ifEmpty { alternatives[0] }
    }

    // ── RecognitionListener ───────────────────────────────────────────

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            lastError = ""
            rmsLevel = 0f
            consecutiveErrors = 0
            // Pre-create next recognizer for zero-gap restart
            prepareNextRecognizer()
        }

        override fun onBeginningOfSpeech() {
            service.feedbackManager.onTrackballStep()
        }

        override fun onRmsChanged(rmsdB: Float) {
            rmsLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            rmsLevel = 0f
        }

        override fun onError(error: Int) {
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "聞き取れませんでした"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "音声入力タイムアウト"
                SpeechRecognizer.ERROR_AUDIO -> "オーディオエラー"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "マイク権限がありません"
                SpeechRecognizer.ERROR_NETWORK -> "ネットワークエラー"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ネットワークタイムアウト"
                SpeechRecognizer.ERROR_SERVER -> "サーバーエラー"
                SpeechRecognizer.ERROR_CLIENT -> "クライアントエラー"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "認識エンジンビジー"
                else -> "エラー ($error)"
            }

            // Silence errors (NO_MATCH, SPEECH_TIMEOUT) are normal in always-on mode
            // — don't count them toward the consecutive error limit
            val isSilenceError = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (!isSilenceError) {
                consecutiveErrors++
            }
            Log.w(TAG, "Recognition error: $msg (code=$error, consecutive=$consecutiveErrors, silence=$isSilenceError)")

            // In continuous mode, auto-restart on recoverable errors
            if (continuousMode && error in RECOVERABLE_ERRORS) {
                partialText = ""
                rmsLevel = 0f
                // Only show error for non-silence errors (don't spam the user)
                if (!isSilenceError) {
                    lastError = msg
                }

                // Give up after too many consecutive real errors
                if (consecutiveErrors > MAX_CONSECUTIVE_ERRORS) {
                    Log.w(TAG, "Too many consecutive errors ($consecutiveErrors), stopping")
                    lastError = msg
                    isListening = false
                    continuousMode = false
                    return
                }

                // Silence errors: restart immediately (always-on behavior)
                // Real errors: exponential backoff
                val delay = if (isSilenceError) {
                    50L // Near-instant restart for silence — Typeless-style always-on
                } else {
                    (100L * (1 shl (consecutiveErrors - 1).coerceAtMost(4)))
                }

                mainHandler.postDelayed({
                    if (continuousMode && isBatteryOk()) {
                        startRecognizer()
                    } else {
                        isListening = false
                        continuousMode = false
                    }
                }, delay)
            } else {
                lastError = msg
                partialText = ""
                rmsLevel = 0f
                isListening = false
                continuousMode = false
            }
        }

        override fun onResults(results: Bundle?) {
            val text = selectBestResult(results)
            writeDiagnostic("SpeechRecognizer.onResults: text='${text.take(80)}' (${text.length} chars)")

            if (text.isNotEmpty()) {
                val asciiRatio = text.count { it.code in 0x20..0x7E }.toFloat() / text.length
                currentLanguage = if (asciiRatio > 0.7f) "en-US" else "ja-JP"

                val converted = convertVoiceCommands(text)
                val spaced = addUtteranceSpacing(converted)
                val withCommas = insertMidSentenceCommas(spaced)
                val processed = smartPunctuation(withCommas)

                val cleaned = space.manus.nacre.ai.LlmPostProcessor.quickClean(processed)
                service.currentInputConnection?.commitText(cleaned, 1)
                committedInSession.append(cleaned)
                // Stage 2: LLM refinement in background
                tryLlmRefinement(cleaned)
                utteranceCount++
            } else {
                writeDiagnostic("SpeechRecognizer.onResults: EMPTY text, skipping")
            }

            rmsLevel = 0f
            lastCommittedPartial = ""
            partialStableCount = 0
            lastPartialText = ""
            consecutiveErrors = 0

            // Zero-gap continuous restart — recognizer restarts instantly
            // so the user never has to tap again. Silence is fine.
            if (continuousMode && isBatteryOk()) {
                startRecognizer()
            } else {
                isListening = false
                continuousMode = false
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = selectBestResult(partialResults)
            if (text.isBlank()) return

            // Keep the latest partial visible in the UI and try to commit only
            // the stable prefix. This gives us Typeless-style segmentation
            // without waiting for final results on every pause.
            val converted = convertVoiceCommands(text)
            partialText = LlmPostProcessor.quickClean(converted)
            tryStreamingCommit(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun requestAudioFocus() {
        val am = service.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        audioFocusRequest = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        am.requestAudioFocus(audioFocusRequest!!)
    }

    private fun releaseAudioFocus() {
        audioFocusRequest?.let {
            val am = service.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            am.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }

    fun bindWhisperService() {
        if (whisperBound) return
        try {
            val intent = android.content.Intent().apply {
                setClassName(service.packageName, "space.manus.nacre.ai.WhisperService")
            }
            val bound = service.bindService(intent, whisperConnection, android.content.Context.BIND_AUTO_CREATE)
            whisperBound = bound
            Log.i(TAG, "bindWhisperService: bound=$bound, package=${service.packageName}")
            writeDiagnostic("bindWhisperService: bound=$bound")
        } catch (e: Exception) {
            Log.e(TAG, "bindWhisperService failed", e)
            writeDiagnostic("bindWhisperService FAILED: ${e.message}")
        }
    }

    fun unbindWhisperService() {
        if (whisperBound) {
            service.unbindService(whisperConnection)
            whisperBound = false
            whisperService = null
        }
    }

    fun bindLlmService() {
        if (llmBound) return
        try {
            val intent = android.content.Intent().apply {
                setClassName(service.packageName, "space.manus.nacre.ai.LlmService")
            }
            val bound = service.bindService(intent, llmConnection, android.content.Context.BIND_AUTO_CREATE)
            llmBound = bound
            writeDiagnostic("bindLlmService: bound=$bound")
        } catch (e: Exception) {
            Log.e(TAG, "bindLlmService failed", e)
            writeDiagnostic("bindLlmService FAILED: ${e.message}")
        }
    }

    fun unbindLlmService() {
        if (llmBound) {
            service.unbindService(llmConnection)
            llmBound = false
            llmService = null
        }
    }

    private fun isBatteryOk(): Boolean {
        val bm = service.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return true
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level > BATTERY_THRESHOLD
    }

    /**
     * Stage 2: Try LLM refinement in background. Priority:
     *   1. Cloud chain (Qwen Max → Gemini Pro → DeepSeek V3) if any API key configured
     *   2. Local in-process LlmService (Qwen 1.5B) as offline fallback
     * First success wins; otherwise the quickClean output stays committed.
     */
    private fun tryLlmRefinement(quickCleanText: String) {
        Thread {
            try {
                val instruction = LlmPostProcessor.DICTATION_CLEANUP_INSTRUCTION

                // Step 1: cloud chain
                val cloudChain = space.manus.nacre.ai.cloud.RefinerFactory.build(service)
                for (refiner in cloudChain) {
                    val start = System.currentTimeMillis()
                    val result = refiner.refine(quickCleanText, instruction, CLOUD_REFINE_TIMEOUT_MS)
                    val elapsed = System.currentTimeMillis() - start
                    if (result.isSuccess) {
                        val refined = result.getOrNull()?.trim().orEmpty()
                        writeDiagnostic("tryLlmRefinement[cloud:${refiner.name}]: ${elapsed}ms, refined='${refined.take(80)}'")
                        applyRefinedIfDifferent(quickCleanText, refined, refiner.name)
                        return@Thread
                    } else {
                        writeDiagnostic("tryLlmRefinement[cloud:${refiner.name}]: FAIL (${elapsed}ms) ${result.exceptionOrNull()?.message}")
                    }
                }

                // Step 2: local AIDL Qwen fallback
                val svc = llmService
                if (svc == null || !svc.isModelLoaded) {
                    writeDiagnostic("tryLlmRefinement: no cloud key + local LLM not loaded, keeping quickClean")
                    return@Thread
                }
                if (svc.isGenerating) {
                    writeDiagnostic("tryLlmRefinement[local]: busy, skipping")
                    return@Thread
                }
                val start = System.currentTimeMillis()
                val refined = refineViaAidl(svc, quickCleanText, LLM_REFINE_TIMEOUT_MS)
                val elapsed = System.currentTimeMillis() - start
                writeDiagnostic("tryLlmRefinement[local]: ${elapsed}ms, refined='${refined?.take(80)}'")
                applyRefinedIfDifferent(quickCleanText, refined.orEmpty(), "local-qwen")
            } catch (e: Exception) {
                writeDiagnostic("tryLlmRefinement: error ${e.javaClass.simpleName}: ${e.message}")
            }
        }.start()
    }

    /** If [refined] differs from the committed quickClean result, delete-and-replace on the UI thread. */
    private fun applyRefinedIfDifferent(committed: String, refined: String, source: String) {
        if (refined.isBlank() || refined == committed) {
            writeDiagnostic("tryLlmRefinement[$source]: no improvement, keeping quickClean")
            return
        }
        Handler(Looper.getMainLooper()).post {
            val ic = service.currentInputConnection ?: return@post
            ic.deleteSurroundingText(committed.length, 0)
            ic.commitText(refined, 1)
            writeDiagnostic("tryLlmRefinement[$source]: replaced quickClean with LLM result")
        }
    }

    /**
     * Blocking wrapper around ILlmService.transform() for the dictation-cleanup
     * instruction. Returns the refined string, or null on timeout/error.
     */
    private fun refineViaAidl(svc: ILlmService, rawText: String, timeoutMs: Long): String? {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        svc.transform(
            rawText,
            LlmPostProcessor.DICTATION_CLEANUP_INSTRUCTION,
            object : ILlmCallback.Stub() {
                override fun onResult(text: String) {
                    result.set(text.trim())
                    latch.countDown()
                }
                override fun onPartialResult(text: String) { /* streaming not used here */ }
                override fun onError(message: String) {
                    writeDiagnostic("refineViaAidl.onError: $message")
                    latch.countDown()
                }
            },
        )
        val finished = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            try { svc.cancelGeneration() } catch (_: Exception) {}
            return null
        }
        return result.get()
    }

    /**
     * Write diagnostic info to a file readable from Termux.
     * File: /sdcard/Download/nacre-voice-debug.txt
     */
    private fun writeDiagnostic(message: String) {
        try {
            val file = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                "nacre-voice-debug.txt"
            )
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date())
            file.appendText("[$timestamp] $message\n")
        } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "VoiceInput"
        private const val BATTERY_THRESHOLD = 10
        private const val MAX_CONSECUTIVE_ERRORS = 15
        // LLM refinement timeout. Qwen 2.5 1.5B Q4_K_M on modern Android typically
        // finishes a ~100-char dictation in 1-3s; allow headroom before giving up.
        private const val LLM_REFINE_TIMEOUT_MS = 8_000L

        // Cloud refiner per-attempt timeout. Gemini Pro / Qwen Max usually finish
        // in 500-1500ms; allow a few seconds of tail before falling through to
        // the next provider in the chain.
        private const val CLOUD_REFINE_TIMEOUT_MS = 6_000
        // No deferred commit timer — Typeless model: commit only on user stop action
        private val RECOVERABLE_ERRORS = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        )
    }
}
