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

    /**
     * Initialize the recognizer with model files from the given directory.
     * @param modelDir Directory containing model.int8.onnx or model.onnx, plus tokens.txt
     * @param vadModelPath Path to silero_vad.onnx
     */
    fun initialize(modelDir: String, vadModelPath: String): Boolean {
        try {
            Log.i(TAG, "Initializing SherpaRecognizer from $modelDir")
            val modelConfig = buildModelConfig(modelDir)
                ?: error("No recognizable ASR model (transducer or SenseVoice) in $modelDir")

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
            Log.i(TAG, "SherpaRecognizer initialized successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SherpaRecognizer", e)
            isInitialized = false
            return false
        }
    }

    /**
     * Build the model config by detecting what's in [modelDir]:
     *  - a Zipformer transducer (encoder/decoder/joiner *.onnx) → ReazonSpeech ja
     *    (preferred, Japanese-specialized);
     *  - otherwise a SenseVoice model.onnx/model.int8.onnx → SenseVoice (fallback).
     * Returns null if neither is present.
     */
    private fun buildModelConfig(modelDir: String): OfflineModelConfig? {
        val dir = java.io.File(modelDir)
        fun pick(prefix: String): java.io.File? =
            dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".onnx") && f.length() > 0 }
                // prefer int8 quant when both exist (smaller, faster on mobile)
                ?.sortedByDescending { it.name.contains("int8") }
                ?.firstOrNull()

        val encoder = pick("encoder")
        val decoder = pick("decoder")
        val joiner = pick("joiner")
        if (encoder != null && decoder != null && joiner != null) {
            Log.i(TAG, "Detected Zipformer transducer (ReazonSpeech): ${encoder.name}")
            return OfflineModelConfig(
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

        val senseVoiceModel = pickSenseVoiceModelFile(modelDir) ?: return null
        Log.i(TAG, "Detected SenseVoice model: $senseVoiceModel")
        return OfflineModelConfig(
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

    private fun pickSenseVoiceModelFile(modelDir: String): String? {
        val candidates = listOf("model.onnx", "model.int8.onnx")
        return candidates
            .map { java.io.File(modelDir, it) }
            .firstOrNull { it.exists() && it.length() > 0 }
            ?.absolutePath
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
        Log.i(TAG, "SherpaRecognizer released")
    }
}
