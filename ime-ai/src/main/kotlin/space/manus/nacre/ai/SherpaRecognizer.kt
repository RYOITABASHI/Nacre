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
    }

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var isInitialized = false

    /** Layout classification of the currently loaded model dir, set by
     *  [initialize]. [AsrModelDetector.Kind.NONE] until a load succeeds. */
    var loadedKind: AsrModelDetector.Kind = AsrModelDetector.Kind.NONE
        private set

    /**
     * Initialize the recognizer with model files from the given directory.
     * @param modelDir Directory containing model.int8.onnx or model.onnx, plus tokens.txt
     * @param vadModelPath Path to silero_vad.onnx
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

        v.acceptWaveform(samples)
        val results = mutableListOf<String>()

        while (!v.empty()) {
            val segment = v.front()
            val text = transcribeSegment(rec, segment.samples)
            if (text.isNotBlank()) {
                results.add(text)
            }
            v.pop()
        }
        return results
    }

    /**
     * Flush remaining audio in VAD buffer and transcribe.
     */
    fun flush(): List<String> {
        val rec = recognizer ?: return emptyList()
        val v = vad ?: return emptyList()

        v.flush()
        val results = mutableListOf<String>()
        while (!v.empty()) {
            val segment = v.front()
            val text = transcribeSegment(rec, segment.samples)
            if (text.isNotBlank()) {
                results.add(text)
            }
            v.pop()
        }
        return results
    }

    fun isSpeechDetected(): Boolean = vad?.isSpeechDetected() ?: false

    fun reset() {
        vad?.reset()
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
        isInitialized = false
        loadedKind = AsrModelDetector.Kind.NONE
        Log.i(TAG, "SherpaRecognizer released")
    }
}
