package space.manus.nacre.ai

import android.util.Log

/**
 * JNI bridge to llama.cpp for local LLM inference.
 * Native library: libnacre-ai.so
 */
object LlamaJni {

    private const val TAG = "LlamaJni"
    private var libraryLoaded = false

    init {
        try {
            System.loadLibrary("nacre-ai")
            libraryLoaded = true
            Log.i(TAG, "Native library loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
        }
    }

    fun isAvailable(): Boolean = libraryLoaded

    /** Load a GGUF model file. Blocks until loaded (3-8 seconds). */
    external fun loadModel(modelPath: String): Boolean

    /** Unload the current model and free memory. */
    external fun unloadModel()

    /** Check if a model is currently loaded. */
    external fun isModelLoaded(): Boolean

    /** Cancel an in-progress generation. */
    external fun cancelGeneration()

    /**
     * Generate text from a prompt.
     * @param prompt the input prompt
     * @param maxTokens maximum tokens to generate
     * @return generated text
     */
    external fun generate(prompt: String, maxTokens: Int): String

    // --- zenz neural conversion (separate model handle from the cleanup LLM) ---

    /** Load the zenz GGUF model. Blocks until loaded. Independent of [loadModel]. */
    external fun loadZenzModel(modelPath: String): Boolean

    /** Unload the zenz model and free its memory. */
    external fun unloadZenzModel()

    /** Check if the zenz model is currently loaded. */
    external fun isZenzModelLoaded(): Boolean

    /**
     * Last zenz load failure detail (empty when the last load succeeded or no
     * load was attempted). Surfaced in Settings so a dead model is visible.
     */
    external fun getZenzLoadError(): String

    /** Cancel an in-progress zenz generation. */
    external fun cancelZenzGeneration()

    /**
     * Generate a conversion with zenz. The prompt must already be in zenz
     * format (see ZenzPrompt: U+EE00 + katakana reading + U+EE01, optionally
     * preceded by U+EE02 + left context); see LlmService.zenzConvert.
     * @return generated text ("" on failure / not loaded)
     */
    external fun generateZenz(prompt: String, maxTokens: Int): String
}
