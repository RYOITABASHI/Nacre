package space.manus.nacre.ai

import android.util.Log

/**
 * High-level KenLM scorer for Japanese conversion candidates.
 *
 * Wraps KenLmJni with convenient methods for scoring Viterbi candidate segments.
 * Thread-safe (JNI layer uses mutex).
 */
class KenLmScorer {

    private var modelLoaded = false

    /**
     * Load a KenLM binary model file.
     * @param modelPath Absolute path to .klm file
     * @return true if loaded successfully
     */
    fun load(modelPath: String): Boolean {
        if (!KenLmJni.isAvailable()) {
            Log.w(TAG, "KenLM native library not available")
            return false
        }
        modelLoaded = KenLmJni.loadModel(modelPath)
        if (modelLoaded) {
            Log.i(TAG, "KenLM model loaded (order=${KenLmJni.getOrder()})")
        }
        return modelLoaded
    }

    fun isReady(): Boolean = KenLmJni.isAvailable() && modelLoaded

    /**
     * Score a single candidate given its word segments.
     * @param segments List of surface forms from Viterbi segmentation
     * @param precedingContext Recent committed text (for cross-sentence context)
     * @return log10 probability sum (higher = more likely)
     */
    fun score(segments: List<String>, precedingContext: String = ""): Float {
        if (!isReady()) return 0f
        val sentence = buildSentence(segments, precedingContext)
        return KenLmJni.scoreSentence(sentence)
    }

    /**
     * Score multiple candidates in batch (more efficient than individual calls).
     * @param candidates List of segment lists
     * @param precedingContext Recent committed text
     * @return FloatArray of log10 probability sums
     */
    fun scoreBatch(candidates: List<List<String>>, precedingContext: String = ""): FloatArray {
        if (!isReady()) return FloatArray(candidates.size) { 0f }
        val sentences = candidates.map { buildSentence(it, precedingContext) }.toTypedArray()
        return KenLmJni.scoreBatch(sentences)
    }

    fun unload() {
        KenLmJni.unloadModel()
        modelLoaded = false
        stateSize = 0
    }

    // --- Incremental scoring for Viterbi integration ---

    private var stateSize: Int = 0

    /** Get state size, caching the result. */
    fun getStateSize(): Int {
        if (stateSize == 0 && isReady()) {
            stateSize = KenLmJni.getStateSize()
        }
        return stateSize
    }

    /** Get begin-of-sentence state. Returns null if not ready. */
    fun getBeginState(): ByteArray? {
        if (!isReady()) return null
        return KenLmJni.getBeginState()
    }

    /**
     * Score a single word incrementally.
     * @return Pair(log10 probability, output state bytes) or null
     */
    fun scoreWordIncremental(inState: ByteArray, word: String): Pair<Float, ByteArray>? {
        if (!isReady()) return null
        val result = KenLmJni.scoreWord(inState, word) ?: return null
        val sz = getStateSize()
        if (result.size < 4 + sz) return null
        val score = java.nio.ByteBuffer.wrap(result, 0, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).float
        val outState = result.copyOfRange(4, 4 + sz)
        return Pair(score, outState)
    }

    /**
     * Score multiple candidates and return per-word normalized scores.
     * Normalization: score / numWords — prevents penalizing longer candidates.
     * @return FloatArray of normalized log10 probability scores
     */
    fun scoreBatchNormalized(candidates: List<List<String>>, precedingContext: String = ""): FloatArray {
        if (!isReady()) return FloatArray(candidates.size) { 0f }
        val sentences = candidates.map { buildSentence(it, precedingContext) }.toTypedArray()
        val rawScores = KenLmJni.scoreBatch(sentences)
        return FloatArray(rawScores.size) { i ->
            val wordCount = candidates[i].size.coerceAtLeast(1)
            rawScores[i] / wordCount  // Per-word normalized score
        }
    }

    private fun buildSentence(segments: List<String>, precedingContext: String): String {
        return buildString {
            if (precedingContext.isNotEmpty()) {
                // For 5-gram model, keep up to 4 preceding words (space-separated)
                // This preserves full n-gram context rather than truncating by char count
                val contextWords = precedingContext.trim().split(" ")
                val keepWords = contextWords.takeLast(4)
                if (keepWords.isNotEmpty()) {
                    append(keepWords.joinToString(" "))
                    append(" ")
                }
            }
            append(segments.joinToString(" "))
        }
    }

    /**
     * Get the n-gram order of the loaded model.
     * @return Order (e.g., 3 for 3-gram, 5 for 5-gram), or 0 if not loaded.
     */
    fun getModelOrder(): Int {
        return try {
            if (isReady()) KenLmJni.getOrder() else 0
        } catch (_: Exception) { 0 }
    }

    companion object {
        private const val TAG = "KenLmScorer"

        /**
         * Find the best available KenLM model file.
         * Priority: external 5-gram > quick paths 5-gram > bundled 3-gram.
         *
         * @param filesDir App's internal files directory
         * @param externalDirs List of external storage directories
         * @return Absolute path to the best model, or null if none found
         */
        fun selectModel(
            filesDir: java.io.File,
            externalDirs: List<java.io.File>,
            quickPaths: List<String> = listOf("/sdcard/Download", "/sdcard/models")
        ): String? {
            // 1. External dirs: 5-gram (highest priority)
            for (dir in externalDirs) {
                val path = java.io.File(dir, "models/japanese-5gram.klm")
                if (path.exists() && path.length() > 0) return path.absolutePath
            }
            // 2. Quick paths for sideloaded 5-gram
            quickPaths.forEach { p ->
                val f = java.io.File(p, "japanese-5gram.klm")
                if (f.exists() && f.length() > 0) return f.absolutePath
            }
            // 3. Internal files dir: 5-gram (copied from external)
            val internal5gram = java.io.File(filesDir, "models/japanese-5gram.klm")
            if (internal5gram.exists() && internal5gram.length() > 0) return internal5gram.absolutePath
            // 4. Compact model (intermediate)
            val compact = java.io.File(filesDir, "models/japanese-compact.klm")
            if (compact.exists() && compact.length() > 0) return compact.absolutePath
            // 5. Bundled 3-gram (fallback)
            val bundled3gram = java.io.File(filesDir, "models/japanese-3gram.klm")
            if (bundled3gram.exists() && bundled3gram.length() > 0) return bundled3gram.absolutePath
            return null
        }
    }
}
