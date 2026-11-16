package space.manus.nacre.ai

import android.util.Log
import com.k2fsa.sherpa.onnx.*

/**
 * Wrapper around sherpa-onnx OfflineRecognizer + Silero VAD.
 * Provides a simple API: feed audio samples, get transcribed text.
 */
class SherpaRecognizer {
    companion object {
        private const val TAG = "SherpaRecognizer"
        private const val SAMPLE_RATE = 16000

        // Force Japanese decoding instead of SenseVoice auto-detect.
        // "auto" frequently misfires kanji-heavy JP speech to zh/yue (shared Han
        // script), producing Chinese hanzi garbage — the dominant accuracy killer
        // for a JP-primary IME. English terms still surface as katakana and are
        // recovered downstream by TECH_TERMS / LLM refinement.
        // Flip to "auto" to A/B the previous behavior. Valid: auto|zh|en|ja|ko|yue.
        private const val RECOGNITION_LANGUAGE = "ja"

        // Audio history retained for VAD segment padding (see
        // VadSegmentPadding). Must cover the single-shot path's longest
        // recording (WhisperService.recordAudioUntilSilence caps at
        // 30 s, fed in one processAudio call before draining) plus a
        // max-length VAD segment (15 s) with slack for streaming mode.
        private const val HISTORY_SECONDS = 36
    }

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var isInitialized = false

    // Recently fed audio + the absolute end of the last window handed
    // to the recognizer. Together they let drainSegments() pad each
    // VAD segment with pre/post-roll (VadSegmentPadding) without ever
    // decoding the same audio twice. Reset in lockstep with the VAD
    // (SpeechSegment.start is relative to the last VAD reset).
    private val audioHistory = AudioHistoryBuffer(HISTORY_SECONDS * SAMPLE_RATE)
    private var decodedUpTo = 0

    /** Layout classification of the currently loaded model dir, set by
     *  [initialize]. [AsrModelDetector.Kind.NONE] until a load succeeds. */
    var loadedKind: AsrModelDetector.Kind = AsrModelDetector.Kind.NONE
        private set

    /**
     * Initialize the recognizer with model files from the given directory.
     * @param modelDir Directory containing model.int8.onnx or model.onnx, plus tokens.txt
     * @param vadModelPath Path to the Silero VAD model (v6 preferred;
     * legacy v5-era file also works — see ModelDownloader.getVadModelPath)
     */
    fun initialize(modelDir: String, vadModelPath: String): Boolean {
        try {
            Log.i(TAG, "Initializing SherpaRecognizer from $modelDir")
            val modelConfig = buildModelConfig(modelDir)
                ?: error("No recognizable ASR model (transducer, NeMo CTC, or SenseVoice) in $modelDir")

            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
                decodingMethod = "greedy_search",
            )
            recognizer = OfflineRecognizer(config = config)

            // Thresholds are the v5-era values, kept deliberately: desktop
            // fixture checks against Silero VAD v6.2.3 on sherpa-onnx 1.12.34
            // (2026-11-16, tools/vad_fixture_check.py) showed identical
            // segment decisions on clean speech and better noise rejection,
            // so no endpointing retune was warranted for the v6 swap.
            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = vadModelPath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.3f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 15.0f,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
            )
            vad = Vad(config = vadConfig)
            // Fresh VAD → its segment indices restart at 0; the
            // padding bookkeeping must restart with it.
            audioHistory.reset()
            decodedUpTo = 0

            isInitialized = true
            loadedKind = AsrModelDetector.detect(java.io.File(modelDir))
            Log.i(TAG, "SherpaRecognizer initialized successfully (kind=$loadedKind)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SherpaRecognizer", e)
            isInitialized = false
            loadedKind = AsrModelDetector.Kind.NONE
            return false
        }
    }

    /**
     * Build the model config from the layout classification of [modelDir]
     * (see [AsrModelDetector] for the rules):
     *  - TRANSDUCER → ReazonSpeech ja Zipformer;
     *  - NEMO_CTC → Parakeet-tdt_ctc-0.6b-ja (OfflineNemoEncDecCtc; the
     *    pinned sherpa-onnx 1.12.34 AAR ships this config class and the
     *    model dispatches on its embedded ONNX metadata);
     *  - SENSEVOICE → SenseVoice (legacy fallback).
     * Returns null when the directory holds no recognizable model.
     */
    private fun buildModelConfig(modelDir: String): OfflineModelConfig? {
        val dir = java.io.File(modelDir)
        fun pick(prefix: String): java.io.File? =
            dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".onnx") && f.length() > 0 }
                // prefer int8 quant when both exist (smaller, faster on mobile)
                ?.sortedByDescending { it.name.contains("int8") }
                ?.firstOrNull()

        return when (AsrModelDetector.detect(dir)) {
            AsrModelDetector.Kind.TRANSDUCER -> {
                val encoder = pick("encoder") ?: return null
                val decoder = pick("decoder") ?: return null
                val joiner = pick("joiner") ?: return null
                Log.i(TAG, "Detected Zipformer transducer (ReazonSpeech): ${encoder.name}")
                OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                        joiner = joiner.absolutePath,
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "transducer",
                )
            }
            AsrModelDetector.Kind.NEMO_CTC -> {
                val model = AsrModelDetector.singleModelFile(dir) ?: return null
                Log.i(TAG, "Detected NeMo CTC model (Parakeet): ${model.name}")
                OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(
                        model = model.absolutePath,
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = 2,
                    provider = "cpu",
                )
            }
            AsrModelDetector.Kind.SENSEVOICE -> {
                val senseVoiceModel = AsrModelDetector.singleModelFile(dir)?.absolutePath ?: return null
                Log.i(TAG, "Detected SenseVoice model: $senseVoiceModel")
                OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = senseVoiceModel,
                        language = RECOGNITION_LANGUAGE,
                        useInverseTextNormalization = true,
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = 2,
                    provider = "cpu",
                )
            }
            AsrModelDetector.Kind.NONE -> null
        }
    }

    fun isReady(): Boolean = isInitialized

    /**
     * Feed audio samples to VAD. Returns list of transcribed segments.
     */
    fun processAudio(samples: FloatArray): List<String> {
        val rec = recognizer ?: return emptyList()
        val v = vad ?: return emptyList()

        // History first: a segment that pops below may need padding
        // from audio fed in this very call (single-shot path feeds a
        // whole recording, then drains).
        audioHistory.append(samples)
        v.acceptWaveform(samples)
        return drainSegments(rec, v)
    }

    /**
     * Flush remaining audio in VAD buffer and transcribe.
     */
    fun flush(): List<String> {
        val rec = recognizer ?: return emptyList()
        val v = vad ?: return emptyList()

        v.flush()
        return drainSegments(rec, v)
    }

    /**
     * Pop every queued VAD segment and transcribe it with pre/post
     * padding (see [VadSegmentPadding] for the measured clipping this
     * fixes and the seam rules). The padding is sliced from
     * [audioHistory]; if the slice is unavailable (evicted — not
     * expected with the 36 s history), the segment's own samples are
     * decoded as before and the seam watermark advances only over the
     * audio actually decoded.
     */
    private fun drainSegments(rec: OfflineRecognizer, v: Vad): List<String> {
        // Collect the whole queue first: knowing the NEXT segment's
        // start lets each window cap its post-roll there, so a window
        // can never consume the following segment's core (at a forced
        // 15 s cut the segments are contiguous — an uncapped post-roll
        // plus the seam watermark would start the next segment
        // mid-word, worse than the unpadded baseline).
        val batch = mutableListOf<SpeechSegment>()
        while (!v.empty()) {
            batch.add(v.front())
            v.pop()
        }
        val results = mutableListOf<String>()
        for ((index, segment) in batch.withIndex()) {
            val segStart = segment.start
            val segEnd = segStart + segment.samples.size
            val nextStart = batch.getOrNull(index + 1)?.start ?: Int.MAX_VALUE
            // No special case for the VAD's max-speech cap is needed:
            // POST_ROLL_SAMPLES (0.1 s) never exceeds the trailing
            // silence sherpa-onnx requires before emitting a segment
            // in any mode (0.3 s normally, 0.1 s at the cap), so the
            // post-roll cannot reach the next segment's speech even
            // when that segment is not queued yet. See VadSegmentPadding.
            val window = VadSegmentPadding.paddedWindow(
                segStart = segStart,
                segEnd = segEnd,
                notBefore = decodedUpTo,
                availableEnd = audioHistory.totalFed,
                endLimit = nextStart,
            )
            if (window.coversWholeSegment(segEnd)) {
                // Entirely inside the previous window's post-roll:
                // already decoded once; decoding again would duplicate
                // words at the seam.
                Log.d(TAG, "segment [$segStart,$segEnd) already covered by previous window, skipping")
            } else if (window.start == segStart && window.end == segEnd) {
                val text = transcribeSegment(rec, segment.samples)
                if (text.isNotBlank()) results.add(text)
                decodedUpTo = maxOf(decodedUpTo, segEnd)
            } else {
                val padded = audioHistory.slice(window.start, window.end)
                if (padded != null) {
                    val text = transcribeSegment(rec, padded)
                    if (text.isNotBlank()) results.add(text)
                    decodedUpTo = maxOf(decodedUpTo, window.end)
                } else {
                    Log.w(TAG, "padding slice [${window.start},${window.end}) unavailable, decoding raw segment")
                    val text = transcribeSegment(rec, segment.samples)
                    if (text.isNotBlank()) results.add(text)
                    decodedUpTo = maxOf(decodedUpTo, segEnd)
                }
            }
        }
        return results
    }

    fun isSpeechDetected(): Boolean = vad?.isSpeechDetected() ?: false

    fun reset() {
        vad?.reset()
        audioHistory.reset()
        decodedUpTo = 0
    }

    private fun transcribeSegment(rec: OfflineRecognizer, samples: FloatArray): String {
        return try {
            val stream = rec.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val result = rec.getResult(stream)
            stream.release()
            result.text.trim()
        } catch (e: Exception) {
            Log.e(TAG, "transcribeSegment EXCEPTION: ${e.message}", e)
            ""
        }
    }

    fun release() {
        try { recognizer?.release() } catch (e: Exception) { Log.e(TAG, "recognizer.release() failed", e) }
        try { vad?.release() } catch (e: Exception) { Log.e(TAG, "vad.release() failed", e) }
        recognizer = null
        vad = null
        audioHistory.reset()
        decodedUpTo = 0
        isInitialized = false
        loadedKind = AsrModelDetector.Kind.NONE
        Log.i(TAG, "SherpaRecognizer released")
    }
}
