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
     * @param modelDir Directory containing model.int8.onnx, tokens.txt
     * @param vadModelPath Path to silero_vad.onnx
     */
    fun initialize(modelDir: String, vadModelPath: String): Boolean {
        try {
            Log.i(TAG, "Initializing SherpaRecognizer from $modelDir")

            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = "$modelDir/model.int8.onnx",
                        language = RECOGNITION_LANGUAGE,
                        useInverseTextNormalization = true,
                    ),
                    tokens = "$modelDir/tokens.txt",
                    numThreads = 2,
                    provider = "cpu",
                ),
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
