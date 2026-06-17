package space.manus.nacre.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Model download manager.
 *
 * Locates SenseVoice, Silero VAD, and KenLM model files.
 * Searches internal storage, then external storage.
 *
 * Models:
 * - SenseVoice int8: ~229MB (model.int8.onnx + tokens.txt in a directory)
 * - Silero VAD: ~629KB (silero_vad.onnx)
 * - KenLM 5-gram: ~561MB (japanese-5gram.klm)
 */
class ModelDownloader(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    data class DownloadProgress(
        val modelName: String,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val isComplete: Boolean = false,
        val error: String? = null,
    ) {
        val percent: Int
            get() = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0
    }

    var onProgress: ((DownloadProgress) -> Unit)? = null

    /**
     * Get the models directory, creating it if needed.
     */
    fun getModelsDir(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Check which models are already downloaded.
     */
    fun getDownloadedModels(): Map<String, Boolean> {
        return mapOf(
            "sensevoice" to (getSenseVoiceModelDir() != null),
            "vad" to (getVadModelPath() != null),
            "llm" to (getLlmModelPath() != null),
            "kenlm" to File(getModelsDir(), KENLM_FILENAME).exists(),
            "kenlm_compact" to File(getModelsDir(), COMPACT_KENLM_FILENAME).exists(),
        )
    }

    // ---- SenseVoice model ----

    /**
     * Get SenseVoice model directory path if it exists.
     * The directory must contain model.onnx or model.int8.onnx, plus tokens.txt.
     */
    fun getSenseVoiceModelDir(): String? {
        Log.i(TAG, "getSenseVoiceModelDir: searching for SenseVoice model")

        // Search candidate directories for one containing a SenseVoice model file
        val candidates = mutableListOf<File>()

        // Internal storage
        candidates.add(File(getModelsDir(), SENSEVOICE_DIR))
        // External files dir
        context.getExternalFilesDir(null)?.let {
            candidates.add(File(it, "models/$SENSEVOICE_DIR"))
        }
        // Common external locations
        val sdcard = android.os.Environment.getExternalStorageDirectory()
        val downloads = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        candidates.addAll(listOf(
            File(downloads, SENSEVOICE_DIR),
            File(sdcard, SENSEVOICE_DIR),
            File("/sdcard/Download/$SENSEVOICE_DIR"),
            File("/storage/emulated/0/Download/$SENSEVOICE_DIR"),
        ))

        for (dir in candidates.distinctBy { it.absolutePath }) {
            if (isSenseVoiceDir(dir)) {
                Log.i(TAG, "getSenseVoiceModelDir: FOUND at ${dir.absolutePath}")
                return dir.absolutePath
            }
        }

        // Recursive scan: look for a directory that is actually SenseVoice.
        // model.onnx is a generic filename, so keep scanning when a non-SenseVoice
        // parent directory is encountered.
        try {
            val foundDir = scanForSenseVoiceDir(sdcard, maxDepth = 4)
            if (foundDir != null) {
                Log.i(TAG, "getSenseVoiceModelDir: FOUND via scan at ${foundDir.absolutePath}")
                return foundDir.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "getSenseVoiceModelDir: scan failed", e)
        }

        Log.w(TAG, "getSenseVoiceModelDir: NOT FOUND")
        return null
    }

    fun getSenseVoiceModelFile(modelDir: String): File? {
        return getSenseVoiceModelFile(File(modelDir))
    }

    private fun getSenseVoiceModelFile(dir: File): File? {
        return listOf("model.onnx", "model.int8.onnx")
            .map { File(dir, it) }
            .firstOrNull { it.exists() && it.length() > 0 }
    }

    private fun isSenseVoiceDir(dir: File): Boolean {
        return try {
            dir.isDirectory &&
                getSenseVoiceModelFile(dir) != null &&
                File(dir, "tokens.txt").exists()
        } catch (_: Exception) { false }
    }

    // ---- Silero VAD model ----

    /**
     * Get Silero VAD model path if it exists.
     */
    fun getVadModelPath(): String? {
        Log.i(TAG, "getVadModelPath: searching for silero_vad.onnx")
        return findModelFile(VAD_FILENAME)
    }

    // ---- KenLM model ----

    /**
     * Download the KenLM 5-gram Japanese language model (~561MB, highest quality).
     * Intended for power users who want Gboard+ level conversion quality.
     */
    fun downloadKenLm(onComplete: (Boolean) -> Unit) {
        downloadModel(
            url = KENLM_URL,
            modelName = "KenLM 日本語5-gram",
            fileName = KENLM_FILENAME,
            onComplete = onComplete,
        )
    }

    /**
     * Download the compact KenLM 3-gram Japanese language model (~161MB).
     * This is the default LM we suggest to most users on first run — it gives
     * a large conversion-quality bump over no-LM fallback while staying small
     * enough for quick download on mobile networks.
     */
    fun downloadCompactKenLm(onComplete: (Boolean) -> Unit) {
        downloadModel(
            url = COMPACT_KENLM_URL,
            modelName = "KenLM 日本語3-gram (compact)",
            fileName = COMPACT_KENLM_FILENAME,
            onComplete = onComplete,
        )
    }

    fun getCompactKenLmModelPath(): String? = findModelFile(COMPACT_KENLM_FILENAME)

    // ---- LLM (Qwen 2.5 1.5B default; Gemma 4 retired) ----
    //
    // The native llama.cpp is pinned to b3500 (July 2024), whose loader does NOT
    // understand the Gemma 4 / Gemma 3n "E2B" architecture — on device it just
    // times out ("did not become ready after 180s") and dictation refinement
    // stays disabled. Gemma 4 is also ~3GB, which does not fit the ~1.6GB free on
    // a busy device. Qwen 2.5 1.5B is Qwen2-arch (loads fine on b3500), ~1.0GB
    // (fits memory), and strong at Japanese — so it is now the default.

    /**
     * Download the default local LLM (Qwen 2.5 1.5B Q4_K_M) used for voice
     * post-processing (dictation cleanup).
     */
    fun downloadLlm(onComplete: (Boolean) -> Unit) {
        downloadQwenLlm(onComplete)
    }

    fun downloadGemma4Llm(onComplete: (Boolean) -> Unit) {
        downloadModel(
            url = LLM_URL,
            modelName = "Gemma 4 E2B Instruct (Q4_K_M)",
            fileName = LLM_FILENAME,
            onComplete = onComplete,
        )
    }

    fun downloadQwenLlm(onComplete: (Boolean) -> Unit) {
        downloadModel(
            url = QWEN_LLM_URL,
            modelName = "Qwen 2.5 1.5B Instruct (Q4_K_M)",
            fileName = QWEN_LLM_FILENAME,
            onComplete = onComplete,
        )
    }

    fun getLlmModelPath(): String? {
        return findModelFile(QWEN_LLM_FILENAME) ?: findModelFile(LLM_FILENAME)
    }

    fun getPreferredLlmModelPaths(): List<String> {
        // Qwen first (loads on the pinned llama.cpp b3500); a stray legacy Gemma
        // file is only tried as a last resort and will simply fail to load.
        return listOfNotNull(
            findModelFile(QWEN_LLM_FILENAME),
            findModelFile(LLM_FILENAME),
        ).distinct()
    }

    /**
     * Remove the obsolete Gemma 4 GGUF (~3GB). It cannot load on the pinned
     * llama.cpp b3500 and only wastes internal storage. Nacre-private file —
     * not shared with Shelly or any other app.
     */
    fun deleteObsoleteLlmModels() {
        val obsolete = findModelFile(LLM_FILENAME) ?: return
        try {
            val file = File(obsolete)
            val mb = file.length() / 1024 / 1024
            if (file.delete()) {
                Log.i(TAG, "Deleted obsolete Gemma 4 model ($obsolete, ${mb}MB)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete obsolete Gemma 4 model: ${e.message}")
        }
    }

    /**
     * Provision the personal default model set without blocking UI/IME startup.
     * Compact KenLM is the default conversion model; Qwen 2.5 1.5B is the default
     * local LLM (b3500-compatible, fits memory). Any obsolete Gemma 4 GGUF is
     * deleted to reclaim ~3GB.
     */
    fun ensureDefaultModelsDownloaded(
        downloadCompactKenLm: Boolean = true,
        downloadLlm: Boolean = true,
    ) {
        synchronized(ModelDownloader::class.java) {
            if (autoProvisionStarted) return
            autoProvisionStarted = true
        }

        scope.launch {
            deleteObsoleteLlmModels()
            if (downloadCompactKenLm && getCompactKenLmModelPath() == null) {
                downloadModelInternal(
                    url = COMPACT_KENLM_URL,
                    modelName = "KenLM 日本語3-gram (compact)",
                    fileName = COMPACT_KENLM_FILENAME,
                )
            }
            if (downloadLlm && findModelFile(QWEN_LLM_FILENAME) == null) {
                downloadModelInternal(
                    url = QWEN_LLM_URL,
                    modelName = "Qwen 2.5 1.5B Instruct (Q4_K_M)",
                    fileName = QWEN_LLM_FILENAME,
                )
            }
        }
    }

    /**
     * Get KenLM model file if it exists.
     */
    fun getKenLmModel(): File? {
        val file = File(getModelsDir(), KENLM_FILENAME)
        return if (file.exists()) file else null
    }

    /**
     * Get KenLM model path if it exists.
     */
    fun getKenLmModelPath(): String? = findModelFile(KENLM_FILENAME)

    // ---- Generic download ----

    fun downloadModel(
        url: String,
        modelName: String,
        fileName: String,
        onComplete: (Boolean) -> Unit,
    ) {
        currentJob = scope.launch {
            val ok = downloadModelInternal(url, modelName, fileName)
            withContext(Dispatchers.Main) {
                onComplete(ok)
            }
        }
    }

    private suspend fun downloadModelInternal(
        url: String,
        modelName: String,
        fileName: String,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val outFile = File(getModelsDir(), fileName)
            val tmpFile = File(getModelsDir(), "$fileName.tmp")

            try {
                if (outFile.exists() && outFile.length() > 0) {
                    withContext(Dispatchers.Main) {
                        onProgress?.invoke(
                            DownloadProgress(modelName, outFile.length(), outFile.length(), isComplete = true),
                        )
                    }
                    return@withContext true
                }

                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30000
                connection.readTimeout = 30000

                if (tmpFile.exists()) {
                    connection.setRequestProperty("Range", "bytes=${tmpFile.length()}-")
                }

                connection.connect()

                val totalBytes = connection.contentLengthLong + (if (tmpFile.exists()) tmpFile.length() else 0)
                var bytesDownloaded = if (tmpFile.exists()) tmpFile.length() else 0L

                val input = connection.inputStream
                val output = FileOutputStream(tmpFile, tmpFile.exists())

                val buffer = ByteArray(8192)
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (!currentCoroutineContext().isActive) {
                        input.close()
                        output.close()
                        connection.disconnect()
                        return@withContext false
                    }

                    output.write(buffer, 0, bytesRead)
                    bytesDownloaded += bytesRead

                    withContext(Dispatchers.Main) {
                        onProgress?.invoke(
                            DownloadProgress(modelName, bytesDownloaded, totalBytes),
                        )
                    }
                }

                output.close()
                input.close()
                connection.disconnect()

                tmpFile.renameTo(outFile)

                withContext(Dispatchers.Main) {
                    onProgress?.invoke(
                        DownloadProgress(modelName, totalBytes, totalBytes, isComplete = true),
                    )
                }

                Log.i(TAG, "Model downloaded: $fileName (${totalBytes / 1024 / 1024}MB)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: $modelName", e)
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(
                        DownloadProgress(modelName, 0, 0, error = e.message),
                    )
                }
                false
            }
        }
    }

    fun cancelDownload() {
        currentJob?.cancel()
        currentJob = null
    }

    fun deleteModels() {
        val dir = getModelsDir()
        dir.listFiles()?.forEach { it.delete() }
        Log.i(TAG, "All models deleted")
    }

    fun getModelsSize(): Long {
        val dir = getModelsDir()
        return dir.listFiles()?.sumOf { it.length() } ?: 0
    }

    fun destroy() {
        scope.cancel()
    }

    // ---- File search ----

    /**
     * Search for a model file by name.
     * 1. Internal storage (app models dir)
     * 2. Common locations (Download, etc.)
     * 3. Recursive scan of /sdcard (max depth 4)
     * 4. MediaStore query
     */
    private fun findModelFile(filename: String): String? {
        Log.i(TAG, "findModelFile: searching for '$filename'")

        val externalModels = context.getExternalFilesDir(null)?.let { File(it, "models/$filename") }
        if (externalModels != null && externalModels.exists() && externalModels.length() > 0) {
            return externalModels.absolutePath
        }

        val internal = File(context.filesDir, "models/$filename")
        if (internal.exists() && internal.length() > 0) return internal.absolutePath

        val sdcard = android.os.Environment.getExternalStorageDirectory()
        val downloads = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val quickPaths = listOf(
            File(downloads, filename),
            File(downloads, "nacre-models/$filename"),
            File(sdcard, filename),
            File(sdcard, "models/$filename"),
            File("/sdcard/Download/$filename"),
            File("/storage/emulated/0/Download/$filename"),
            File("/sdcard/$filename"),
        ).distinctBy { it.absolutePath }

        for (path in quickPaths) {
            val exists = try { path.exists() } catch (_: Exception) { false }
            val readable = try { path.canRead() } catch (_: Exception) { false }
            val size = try { if (exists) path.length() else 0L } catch (_: Exception) { 0L }
            if (exists && readable && size > 0) {
                Log.i(TAG, "findModelFile: FOUND at ${path.absolutePath}")
                return path.absolutePath
            }
        }

        try {
            val found = scanForFile(sdcard, filename, maxDepth = 4)
            if (found != null) {
                Log.i(TAG, "findModelFile: FOUND via scan at ${found.absolutePath}")
                return found.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "findModelFile: scan failed", e)
        }

        try {
            val found = findViaMediaStore(filename)
            if (found != null) {
                Log.i(TAG, "findModelFile: FOUND via MediaStore at ${found.absolutePath}")
                return found.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "findModelFile: MediaStore query failed", e)
        }

        Log.w(TAG, "findModelFile: '$filename' NOT FOUND")
        return null
    }

    private fun findViaMediaStore(filename: String): File? {
        val resolver = context.contentResolver
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            android.provider.MediaStore.Downloads._ID,
            android.provider.MediaStore.Downloads.DISPLAY_NAME,
            android.provider.MediaStore.Downloads.SIZE,
        )
        val selection = "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?"
        val selectionArgs = arrayOf(filename)

        resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID)
                val sizeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads.SIZE)
                val id = cursor.getLong(idCol)
                val size = cursor.getLong(sizeCol)

                val uri = android.content.ContentUris.withAppendedId(collection, id)
                val internalFile = File(getModelsDir(), filename)

                if (internalFile.exists() && internalFile.length() == size) {
                    return internalFile
                }

                resolver.openInputStream(uri)?.use { input ->
                    internalFile.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }

                if (internalFile.exists() && internalFile.length() > 0) {
                    return internalFile
                }
            }
        }
        return null
    }

    private fun scanForFile(root: File, filename: String, maxDepth: Int): File? {
        if (maxDepth <= 0 || !root.isDirectory) return null
        val skipDirs = setOf("Android", ".thumbnails", ".cache", "cache", "DCIM", "Pictures", "Music", "Ringtones", "Alarms", "Notifications")
        val children = root.listFiles() ?: return null
        for (f in children) {
            if (f.isFile && f.name == filename) return f
        }
        for (f in children) {
            if (f.isDirectory && f.name !in skipDirs && !f.name.startsWith(".")) {
                val found = scanForFile(f, filename, maxDepth - 1)
                if (found != null) return found
            }
        }
        return null
    }

    private fun scanForSenseVoiceDir(root: File, maxDepth: Int): File? {
        if (maxDepth <= 0 || !root.isDirectory) return null
        if (isSenseVoiceDir(root)) return root

        val skipDirs = setOf("Android", ".thumbnails", ".cache", "cache", "DCIM", "Pictures", "Music", "Ringtones", "Alarms", "Notifications")
        val children = root.listFiles() ?: return null
        for (f in children) {
            if (f.isDirectory && f.name !in skipDirs && !f.name.startsWith(".")) {
                val found = scanForSenseVoiceDir(f, maxDepth - 1)
                if (found != null) return found
            }
        }
        return null
    }

    companion object {
        private const val TAG = "ModelDownloader"
        const val SENSEVOICE_DIR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
        const val VAD_FILENAME = "silero_vad.onnx"
        const val KENLM_FILENAME = "japanese-5gram.klm"
        const val COMPACT_KENLM_FILENAME = "japanese-compact.klm"
        const val LLM_FILENAME = "gemma-4-E2B-it-Q4_K_M.gguf"
        const val QWEN_LLM_FILENAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        const val KENLM_URL = "https://github.com/RYOITABASHI/Nacre/releases/download/v0.1.0-models/japanese-5gram.klm"
        const val COMPACT_KENLM_URL = "https://github.com/RYOITABASHI/Nacre/releases/download/v0.1.0-models/japanese-compact.klm"
        const val LLM_URL = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf"
        const val QWEN_LLM_URL = "https://github.com/RYOITABASHI/Nacre/releases/download/v0.1.0-models/qwen2.5-1.5b-instruct-q4_k_m.gguf"
        @Volatile private var autoProvisionStarted = false
    }
}
