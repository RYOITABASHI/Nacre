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

    /**
     * Check if AI addon is purchased. Must be verified before downloading.
     * Uses SharedPreferences set by BillingManager in the app module.
     */
    fun isAiAddonPurchased(): Boolean {
        val prefs = context.getSharedPreferences("nacre_billing", Context.MODE_PRIVATE)
        return prefs.getBoolean("ai_addon_purchased", false)
    }

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
            "llm" to File(getModelsDir(), LLM_FILENAME).exists(),
            "kenlm" to File(getModelsDir(), KENLM_FILENAME).exists(),
        )
    }

    // ---- SenseVoice model ----

    /**
     * Get SenseVoice model directory path if it exists.
     * The directory must contain model.int8.onnx and tokens.txt.
     */
    fun getSenseVoiceModelDir(): String? {
        Log.i(TAG, "getSenseVoiceModelDir: searching for SenseVoice model")

        // Search candidate directories for one containing model.int8.onnx
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

        // Recursive scan: look for model.int8.onnx
        try {
            val found = scanForFile(sdcard, "model.int8.onnx", maxDepth = 4)
            if (found != null) {
                val dir = found.parentFile
                if (dir != null && isSenseVoiceDir(dir)) {
                    Log.i(TAG, "getSenseVoiceModelDir: FOUND via scan at ${dir.absolutePath}")
                    return dir.absolutePath
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "getSenseVoiceModelDir: scan failed", e)
        }

        Log.w(TAG, "getSenseVoiceModelDir: NOT FOUND")
        return null
    }

    private fun isSenseVoiceDir(dir: File): Boolean {
        return try {
            dir.isDirectory &&
                File(dir, "model.int8.onnx").let { it.exists() && it.length() > 0 } &&
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
     * Download the KenLM 5-gram Japanese language model.
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
        if (!isAiAddonPurchased()) {
            Log.w(TAG, "AI addon not purchased, refusing download")
            onComplete(false)
            return
        }

        currentJob = scope.launch {
            val outFile = File(getModelsDir(), fileName)
            val tmpFile = File(getModelsDir(), "$fileName.tmp")

            try {
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
                    if (!isActive) {
                        input.close()
                        output.close()
                        connection.disconnect()
                        return@launch
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
                    onComplete(true)
                }

                Log.i(TAG, "Model downloaded: $fileName (${totalBytes / 1024 / 1024}MB)")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: $modelName", e)
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(
                        DownloadProgress(modelName, 0, 0, error = e.message),
                    )
                    onComplete(false)
                }
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

    /**
     * Extract bundled KenLM 3-gram model from APK assets to internal storage.
     * Called on first launch. Skips if already extracted.
     *
     * @return Absolute path to extracted model, or null if extraction failed or no bundled model
     */
    fun extractBundledKenLm(): String? {
        val targetDir = File(context.filesDir, "models")
        targetDir.mkdirs()
        val target = File(targetDir, KENLM_3GRAM_FILENAME)
        if (target.exists() && target.length() > 1_000_000) return target.absolutePath

        return try {
            context.assets.open("models/$KENLM_3GRAM_FILENAME").use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 65536)
                }
            }
            if (target.exists() && target.length() > 0) {
                Log.i(TAG, "Bundled KenLM 3-gram extracted (${target.length() / 1024 / 1024}MB)")
                target.absolutePath
            } else null
        } catch (e: java.io.FileNotFoundException) {
            // No bundled model in assets — expected when model hasn't been added yet
            Log.i(TAG, "No bundled KenLM 3-gram in assets (will be added after CI training)")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract bundled KenLM: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "ModelDownloader"
        const val SENSEVOICE_DIR = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
        const val VAD_FILENAME = "silero_vad.onnx"
        const val KENLM_FILENAME = "japanese-5gram.klm"
        const val KENLM_3GRAM_FILENAME = "japanese-3gram.klm"
        const val LLM_FILENAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        const val KENLM_URL = "https://github.com/RYOITABASHI/Nacre/releases/download/v0.1.0-models/japanese-5gram.klm"
    }
}
