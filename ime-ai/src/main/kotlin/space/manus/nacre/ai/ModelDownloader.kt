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
 * - Silero VAD v6: ~2.3MB (silero_vad_v6.onnx; legacy silero_vad.onnx accepted as fallback)
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
            "parakeet" to (getParakeetModelDir() != null),
            "vad" to (getVadModelPath() != null),
            "llm" to (getLlmModelPath() != null),
            "qwen3" to (getQwen3ModelPath() != null),
            "kenlm" to File(getModelsDir(), KENLM_FILENAME).exists(),
            "kenlm_compact" to File(getModelsDir(), COMPACT_KENLM_FILENAME).exists(),
            "zenz" to (getZenzModelPath() != null),
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
        // Layout classification is shared with SherpaRecognizer: a Parakeet
        // (NeMo CTC) directory has the same model.int8.onnx + tokens.txt
        // shape but must NOT be claimed as SenseVoice here.
        return try {
            AsrModelDetector.detect(dir) == AsrModelDetector.Kind.SENSEVOICE
        } catch (_: Exception) { false }
    }

    // ---- ReazonSpeech ja Zipformer ----

    /** True when [dir] holds a Zipformer transducer (encoder/decoder/joiner + tokens). */
    private fun isReazonSpeechDir(dir: File): Boolean {
        return try {
            AsrModelDetector.detect(dir) == AsrModelDetector.Kind.TRANSDUCER
        } catch (_: Exception) { false }
    }

    /** Path of the ReazonSpeech model directory if present, else null. */
    fun getReazonSpeechModelDir(): String? {
        val candidates = mutableListOf<File>()
        candidates.add(File(getModelsDir(), REAZONSPEECH_DIR))
        context.getExternalFilesDir(null)?.let { candidates.add(File(it, "models/$REAZONSPEECH_DIR")) }
        val downloads = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS,
        )
        candidates.addAll(
            listOf(
                File(downloads, REAZONSPEECH_DIR),
                File("/sdcard/Download/$REAZONSPEECH_DIR"),
            ),
        )
        for (dir in candidates.distinctBy { it.absolutePath }) {
            if (isReazonSpeechDir(dir)) {
                Log.i(TAG, "getReazonSpeechModelDir: FOUND at ${dir.absolutePath}")
                return dir.absolutePath
            }
        }
        return null
    }

    /**
     * Preferred ASR model dir, highest priority first: Parakeet (opt-in
     * high-accuracy upgrade) → ReazonSpeech transducer → legacy SenseVoice.
     * SherpaRecognizer classifies the model type from the directory layout
     * (see [AsrModelDetector]).
     */
    fun getPreferredAsrModelDir(): String? = getAsrModelDirCandidates().firstOrNull()

    /**
     * All present ASR model dirs in priority order (Parakeet, ReazonSpeech,
     * SenseVoice), deduplicated. The voice startup path walks this list so a
     * model that fails to load falls back to the next one instead of
     * degrading the whole session.
     */
    fun getAsrModelDirCandidates(): List<String> =
        listOfNotNull(getParakeetModelDir(), getReazonSpeechModelDir(), getSenseVoiceModelDir())
            .distinct()

    // ---- Parakeet-tdt_ctc-0.6b-ja (opt-in high-accuracy ASR upgrade) ----
    //
    // NVIDIA Parakeet FastConformer TDT-CTC (CTC side), int8, ~655MB.
    // Measured 2026-11-15 on JSUT basic5000 (first 100 utterances, same
    // sherpa-onnx 1.12.34 runtime, punctuation-stripped CER): 7.46% vs
    // ReazonSpeech 8.10%, decode speed on par; the cost is size (about
    // 3.9x ReazonSpeech) and a slower cold load. License: CC-BY-4.0 —
    // attribution in THIRD_PARTY_LICENSES.md and the Settings AI Models
    // section.
    //
    // NEVER bundled and NEVER auto-downloaded: at 655MB it is an explicit
    // Settings opt-in (Settings → AI Models → 音声認識), with the size
    // shown before the user confirms. ensureDefaultModelsDownloaded must
    // not reference it.

    /** True when [dir] holds the NeMo CTC (Parakeet) package layout. */
    private fun isParakeetDir(dir: File): Boolean {
        return try {
            AsrModelDetector.detect(dir) == AsrModelDetector.Kind.NEMO_CTC
        } catch (_: Exception) { false }
    }

    /** Path of the Parakeet model directory if present, else null. */
    fun getParakeetModelDir(): String? {
        val candidates = mutableListOf<File>()
        candidates.add(File(getModelsDir(), PARAKEET_DIR))
        context.getExternalFilesDir(null)?.let { candidates.add(File(it, "models/$PARAKEET_DIR")) }
        val downloads = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS,
        )
        candidates.addAll(
            listOf(
                File(downloads, PARAKEET_DIR),
                File("/sdcard/Download/$PARAKEET_DIR"),
            ),
        )
        for (dir in candidates.distinctBy { it.absolutePath }) {
            if (isParakeetDir(dir)) {
                Log.i(TAG, "getParakeetModelDir: FOUND at ${dir.absolutePath}")
                return dir.absolutePath
            }
        }
        return null
    }

    /**
     * Download the Parakeet package (model.int8.onnx ~655MB + tokens.txt)
     * into the models dir. Resumable via the shared .tmp/Range mechanism;
     * progress flows through [onProgress] per file. User-initiated only —
     * call sites must show the size and get an explicit confirmation first.
     */
    fun downloadParakeet(onComplete: (Boolean) -> Unit) {
        currentJob = scope.launch {
            val dir = File(getModelsDir(), PARAKEET_DIR)
            dir.mkdirs()
            val modelOk = downloadModelInternal(
                url = PARAKEET_MODEL_URL,
                modelName = "Parakeet 高精度音声認識モデル",
                fileName = "$PARAKEET_DIR/$PARAKEET_MODEL_FILENAME",
            )
            val tokensOk = if (modelOk) {
                downloadModelInternal(
                    url = PARAKEET_TOKENS_URL,
                    modelName = "Parakeet tokens",
                    fileName = "$PARAKEET_DIR/tokens.txt",
                )
            } else {
                false
            }
            val ok = modelOk && tokensOk && getParakeetModelDir() != null
            if (!ok) {
                Log.w(TAG, "Parakeet download incomplete (model=$modelOk, tokens=$tokensOk); .tmp kept for resume")
            }
            withContext(Dispatchers.Main) {
                onComplete(ok)
            }
        }
    }

    /** Download the ReazonSpeech ja int8 model (~160MB) into the models dir if missing. */
    private suspend fun downloadReazonSpeechIfMissing() {
        if (getReazonSpeechModelDir() != null) return
        val dir = File(getModelsDir(), REAZONSPEECH_DIR)
        dir.mkdirs()
        val files = listOf(
            Triple(REAZON_ENCODER_URL, "$REAZONSPEECH_DIR/$REAZON_ENCODER", "ReazonSpeech encoder"),
            Triple(REAZON_DECODER_URL, "$REAZONSPEECH_DIR/$REAZON_DECODER", "ReazonSpeech decoder"),
            Triple(REAZON_JOINER_URL, "$REAZONSPEECH_DIR/$REAZON_JOINER", "ReazonSpeech joiner"),
            Triple(REAZON_TOKENS_URL, "$REAZONSPEECH_DIR/tokens.txt", "ReazonSpeech tokens"),
        )
        for ((url, fileName, name) in files) {
            // Abort early if any part fails (e.g. network drop on the 147MB encoder);
            // .tmp is preserved so the next ensureDefault call resumes via Range.
            if (!downloadModelInternal(url = url, modelName = name, fileName = fileName)) {
                Log.w(TAG, "ReazonSpeech download incomplete ($name failed); will retry next launch")
                return
            }
        }
        Log.i(TAG, "ReazonSpeech ja model provisioned")
    }

    // ---- Silero VAD model ----

    /**
     * Get Silero VAD model path if it exists.
     *
     * Prefers the v6 model ([VAD_V6_FILENAME]). The legacy v5-era file
     * ([VAD_FILENAME]) is still accepted, so a device whose v6 download
     * has not completed (or has failed) keeps working exactly as before —
     * the VAD is never a hard dependency of the upgrade.
     */
    fun getVadModelPath(): String? {
        val v6 = findVadV6Path()
        if (v6 != null) {
            Log.i(TAG, "getVadModelPath: using Silero VAD v6 at $v6")
            return v6
        }
        Log.i(TAG, "getVadModelPath: no valid v6 file, searching for legacy silero_vad.onnx")
        return findModelFile(VAD_FILENAME)
    }

    /**
     * Locate the v6 VAD file, accepting only the exact published size
     * ([VAD_V6_BYTES]). The downloader renames its .tmp into place without
     * a length check, so a truncated v6 download is a real possibility —
     * and it must never mask a working legacy model, nor hide a valid v6
     * copy sitting in a later search location. The size predicate is
     * therefore applied during the candidate search itself, not just to
     * whichever file the search happens to find first.
     */
    private fun findVadV6Path(): String? {
        val valid = findModelFile(VAD_V6_FILENAME) { it.length() == VAD_V6_BYTES }
        if (valid == null && findModelFile(VAD_V6_FILENAME) != null) {
            Log.w(TAG, "findVadV6Path: v6-named file(s) present but none matches the expected size $VAD_V6_BYTES; ignoring")
        }
        return valid
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

    // ---- LLM (Qwen 2.5 1.5B default; Qwen3 1.7B opt-in; Gemma 4 retired) ----
    //
    // History: the native llama.cpp was pinned to b3500 (July 2024), whose
    // loader did NOT understand the Gemma 4 / Gemma 3n "E2B" architecture —
    // on device it just timed out ("did not become ready after 180s") and
    // dictation refinement stayed disabled. Gemma 4 is also ~3GB, which does
    // not fit the ~1.6GB free on a busy device, so it stays retired even
    // after the unpin to b12381 (tools/llama-pin.txt). Qwen 2.5 1.5B is
    // ~1.0GB (fits memory) and strong at Japanese — so it is the default.
    // Qwen3 1.7B (below) became loadable with the b12381 unpin and is an
    // opt-in upgrade: never bundled, never auto-downloaded, used only when
    // downloaded AND selected (see [RefineModel]).

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

    // ---- Qwen3 1.7B (opt-in refinement upgrade) ----
    //
    // Qwen3-1.7B Q4_K_M (unsloth GGUF packaging of Qwen's Apache-2.0
    // weights), 1,107,409,472 bytes, sha256
    // b139949c5bd74937ad8ed8c8cf3d9ffb1e99c866c823204dc42c0d91fa181897
    // (HF LFS oid, verified 2026-11-15). Requires llama.cpp b5092+, i.e.
    // the b12381 pin — it cannot load on the old b3500 pin. Like Parakeet
    // it is NEVER bundled and NEVER auto-downloaded: at ~1.1GB it is an
    // explicit Settings opt-in (Settings → AI Models, size shown before
    // confirming). ensureDefaultModelsDownloaded must not reference it.
    // It is used only when downloaded AND selected via [RefineModel];
    // Qwen 2.5 stays the fallback (see [getPreferredLlmModelPaths]).
    // Attribution: THIRD_PARTY_LICENSES.md + the Settings AI Models
    // section.

    /** Download Qwen3 1.7B (Q4_K_M, ~1.1GB). User-initiated only. */
    fun downloadQwen3Llm(onComplete: (Boolean) -> Unit) {
        downloadModel(
            url = QWEN3_LLM_URL,
            modelName = "Qwen3 1.7B Instruct (Q4_K_M)",
            fileName = QWEN3_LLM_FILENAME,
            onComplete = onComplete,
        )
    }

    fun getQwen3ModelPath(): String? = findModelFile(QWEN3_LLM_FILENAME)

    // ---- zenz neural conversion (zenz-v3.2-small, GGUF Q5_K_M, ~74MB) ----
    //
    // zenz is a GPT-2-arch kana-kanji converter (azooKey Zenzai). It needs the
    // "gpt2-small-japanese-char" pre-tokenizer, which stock llama.cpp rejects;
    // the repo's llama build enables it via a configure-time patch (see
    // ime-ai/src/main/cpp/CMakeLists.txt). License: CC-BY-SA-4.0 — attribution
    // in THIRD_PARTY_LICENSES.md and the Settings AI Models section. The model
    // is downloaded by the user, never bundled in the APK.

    /** Download zenz-v3.2-small (Q5_K_M) from Hugging Face. */
    fun downloadZenz(onComplete: (Boolean) -> Unit) {
        downloadModel(
            url = ZENZ_URL,
            modelName = "zenz-v3.2-small (Q5_K_M)",
            fileName = ZENZ_FILENAME,
            onComplete = onComplete,
        )
    }

    fun getZenzModelPath(): String? = findModelFile(ZENZ_FILENAME)

    fun getPreferredLlmModelPaths(): List<String> {
        // The user's refinement-model selection ([RefineModel]) goes first:
        // Qwen3 only when it is both selected and downloaded. Qwen 2.5 is
        // always next, so a Qwen3 load failure falls back to it within the
        // same session (the voice startup path walks this list in order).
        // A stray legacy Gemma file is only tried as a last resort.
        val selectedFirst = if (RefineModel.isQwen3Selected(context)) {
            findModelFile(QWEN3_LLM_FILENAME)
        } else {
            null
        }
        return listOfNotNull(
            selectedFirst,
            findModelFile(QWEN_LLM_FILENAME),
            findModelFile(LLM_FILENAME),
        ).distinct()
    }

    /**
     * Remove the obsolete Gemma 4 GGUF (~3GB). It could not load on the old
     * b3500 pin and at ~3GB it only wastes internal storage. Nacre-private
     * file — not shared with Shelly or any other app.
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
     * local LLM (fits memory). Qwen3 is opt-in only and is never provisioned
     * here. Any obsolete Gemma 4 GGUF is deleted to reclaim ~3GB.
     */
    fun ensureDefaultModelsDownloaded(
        downloadCompactKenLm: Boolean = true,
        downloadLlm: Boolean = true,
        downloadZenz: Boolean = true,
    ) {
        synchronized(ModelDownloader::class.java) {
            if (autoProvisionStarted) return
            autoProvisionStarted = true
        }

        // Voice input switched off (issue #24): no speech-recognition, VAD or
        // refinement-LLM downloads — those are voice-only, and the switch
        // exists for devices without room for them.
        val voiceEnabled = VoiceInputSettings.isEnabled(context)

        scope.launch {
            deleteObsoleteLlmModels()
            if (voiceEnabled) provisionVoiceModels()
            if (downloadCompactKenLm && getCompactKenLmModelPath() == null) {
                downloadModelInternal(
                    url = COMPACT_KENLM_URL,
                    modelName = "KenLM 日本語3-gram (compact)",
                    fileName = COMPACT_KENLM_FILENAME,
                )
            }
            if (voiceEnabled && downloadLlm && findModelFile(QWEN_LLM_FILENAME) == null) {
                downloadModelInternal(
                    url = QWEN_LLM_URL,
                    modelName = "Qwen 2.5 1.5B Instruct (Q4_K_M)",
                    fileName = QWEN_LLM_FILENAME,
                )
            }
            // zenz neural conversion (~74MB) — small next to the models above,
            // and it is the primary conversion-quality lever, so provision it
            // with the default set.
            if (downloadZenz && findModelFile(ZENZ_FILENAME) == null) {
                downloadModelInternal(
                    url = ZENZ_URL,
                    modelName = "zenz-v3.2-small (Q5_K_M)",
                    fileName = ZENZ_FILENAME,
                )
            }
        }
    }

    /** Speech-recognition side of the default set: the VAD and the ASR model. */
    private suspend fun provisionVoiceModels() {
        // Provision the Silero VAD into INTERNAL storage so ASR no longer
        // depends on /sdcard + MANAGE_EXTERNAL_STORAGE (which is lost on
        // reinstall — without the VAD, loadModel is skipped and ASR silently
        // falls back to the system SpeechRecognizer).
        // Target is the v6 model. If its download fails, fall back to the
        // legacy file so a fresh install is never left without a VAD;
        // devices that already hold the legacy file keep using it (via
        // getVadModelPath) until the v6 download lands.
        if (findVadV6Path() == null) {
            // Drop a wrong-size leftover at the download target first:
            // downloadModelInternal treats any existing non-empty target
            // as complete, so without this a truncated v6 file would
            // block its own repair forever.
            val stale = File(getModelsDir(), VAD_V6_FILENAME)
            if (stale.exists() && stale.length() != VAD_V6_BYTES) {
                Log.w(TAG, "Removing wrong-size VAD v6 leftover (${stale.length()} bytes)")
                stale.delete()
            }
            downloadModelInternal(url = VAD_V6_URL, modelName = "Silero VAD v6", fileName = VAD_V6_FILENAME)
            // Decide by the validated lookup, not the download result:
            // downloadModelInternal can report success for a truncated
            // body (it never checks the received length), so re-check
            // whether any usable VAD exists before giving up.
            if (getVadModelPath() == null) {
                downloadModelInternal(url = VAD_URL, modelName = "Silero VAD", fileName = VAD_FILENAME)
            }
        }
        // ASR is the highest-leverage model — provision the ja-specialized
        // ReazonSpeech transducer first (replaces SenseVoice for Japanese).
        downloadReazonSpeechIfMissing()
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
    private fun findModelFile(filename: String, accept: (File) -> Boolean = { true }): String? {
        Log.i(TAG, "findModelFile: searching for '$filename'")

        val externalModels = context.getExternalFilesDir(null)?.let { File(it, "models/$filename") }
        if (externalModels != null && externalModels.exists() && externalModels.length() > 0 && accept(externalModels)) {
            return externalModels.absolutePath
        }

        val internal = File(context.filesDir, "models/$filename")
        if (internal.exists() && internal.length() > 0 && accept(internal)) return internal.absolutePath

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
            if (exists && readable && size > 0 && accept(path)) {
                Log.i(TAG, "findModelFile: FOUND at ${path.absolutePath}")
                return path.absolutePath
            }
        }

        try {
            val found = scanForFile(sdcard, filename, maxDepth = 4, accept = accept)
            if (found != null) {
                Log.i(TAG, "findModelFile: FOUND via scan at ${found.absolutePath}")
                return found.absolutePath
            }
        } catch (e: Exception) {
            Log.w(TAG, "findModelFile: scan failed", e)
        }

        try {
            val found = findViaMediaStore(filename, accept)
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

    private fun findViaMediaStore(filename: String, accept: (File) -> Boolean = { true }): File? {
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
            val idCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID)
            val sizeCol = cursor.getColumnIndexOrThrow(android.provider.MediaStore.Downloads.SIZE)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val size = cursor.getLong(sizeCol)

                val uri = android.content.ContentUris.withAppendedId(collection, id)
                val internalFile = File(getModelsDir(), filename)

                if (internalFile.exists() && internalFile.length() == size && accept(internalFile)) {
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
                    if (accept(internalFile)) {
                        return internalFile
                    }
                    // This row's copy is unusable for the caller (e.g. a
                    // truncated VAD v6): keep scanning later rows rather
                    // than letting it mask a valid copy. With the default
                    // accept-everything predicate this is unreachable, so
                    // first-match behavior is preserved for other models.
                    continue
                }

                // The row produced no file at all: preserve the historical
                // behavior of giving up on the first match.
                return null
            }
        }
        return null
    }

    private fun scanForFile(root: File, filename: String, maxDepth: Int, accept: (File) -> Boolean = { true }): File? {
        if (maxDepth <= 0 || !root.isDirectory) return null
        val skipDirs = setOf("Android", ".thumbnails", ".cache", "cache", "DCIM", "Pictures", "Music", "Ringtones", "Alarms", "Notifications")
        val children = root.listFiles() ?: return null
        for (f in children) {
            if (f.isFile && f.name == filename && accept(f)) return f
        }
        for (f in children) {
            if (f.isDirectory && f.name !in skipDirs && !f.name.startsWith(".")) {
                val found = scanForFile(f, filename, maxDepth - 1, accept)
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
        // ReazonSpeech ja Zipformer transducer — Japanese-specialized ASR (35k hrs),
        // far stronger than SenseVoice on JP. int8 ~160MB. Runs on the existing
        // sherpa-onnx engine (offline transducer). Files mirrored into the Nacre
        // v0.1.0-models release as reazonspeech-ja-*; saved locally with clean names.
        const val REAZONSPEECH_DIR = "sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01"
        // Parakeet-tdt_ctc-0.6b-ja int8 (NeMo CTC) — opt-in download only,
        // served from the sherpa-onnx Hugging Face mirror (same package the
        // 2026-11-15 evaluation measured: model.int8.onnx sha256 3addd00e…).
        const val PARAKEET_DIR = "sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8"
        const val PARAKEET_MODEL_FILENAME = "model.int8.onnx"
        const val PARAKEET_MODEL_BYTES = 655_542_604L
        const val PARAKEET_MODEL_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8/resolve/main/model.int8.onnx"
        const val PARAKEET_TOKENS_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt_ctc-0.6b-ja-35000-int8/resolve/main/tokens.txt"
        const val REAZON_ENCODER = "encoder.int8.onnx"
        const val REAZON_DECODER = "decoder.onnx"
        const val REAZON_JOINER = "joiner.int8.onnx"
        private const val MODELS_BASE_URL = "https://github.com/RYOITABASHI/Nacre/releases/download/v0.1.0-models"
        const val REAZON_ENCODER_URL = "$MODELS_BASE_URL/reazonspeech-ja-encoder.int8.onnx"
        const val REAZON_DECODER_URL = "$MODELS_BASE_URL/reazonspeech-ja-decoder.onnx"
        const val REAZON_JOINER_URL = "$MODELS_BASE_URL/reazonspeech-ja-joiner.int8.onnx"
        const val VAD_URL = "$MODELS_BASE_URL/silero_vad.onnx"
        const val VAD_V6_URL = "$MODELS_BASE_URL/silero_vad_v6.onnx"
        // Silero VAD v6.2.3 (snakers4/silero-vad, MIT) — the wheel's
        // silero_vad/data/silero_vad.onnx, mirrored into the v0.1.0-models
        // release as silero_vad_v6.onnx: 2,327,524 bytes, sha256
        // 1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3.
        // Compatibility verified 2026-11-16 against the pinned sherpa-onnx
        // 1.12.34: it loads via the (input/state/sr) path with the 64-sample
        // context prepend, and at the existing VadModelConfig thresholds it
        // reproduces the legacy model's segment decisions on speech fixtures
        // while rejecting noise better (tools/vad_fixture_check.py).
        const val VAD_V6_BYTES = 2_327_524L
        const val REAZON_TOKENS_URL = "$MODELS_BASE_URL/reazonspeech-ja-tokens.txt"
        const val VAD_FILENAME = "silero_vad.onnx"
        const val VAD_V6_FILENAME = "silero_vad_v6.onnx"
        const val KENLM_FILENAME = "japanese-5gram.klm"
        const val COMPACT_KENLM_FILENAME = "japanese-compact.klm"
        const val LLM_FILENAME = "gemma-4-E2B-it-Q4_K_M.gguf"
        const val QWEN_LLM_FILENAME = "qwen2.5-1.5b-instruct-q4_k_m.gguf"
        const val QWEN3_LLM_FILENAME = "qwen3-1.7b-q4_k_m.gguf"
        const val QWEN3_MODEL_BYTES = 1_107_409_472L
        const val QWEN3_LLM_URL = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
        const val KENLM_URL = "https://github.com/RYOITABASHI/Nacre/releases/download/v0.1.0-models/japanese-5gram.klm"
        const val COMPACT_KENLM_URL = "https://github.com/RYOITABASHI/Nacre/releases/download/v0.1.0-models/japanese-compact.klm"
        const val LLM_URL = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf"
        const val QWEN_LLM_URL = "https://github.com/RYOITABASHI/Nacre/releases/download/v0.1.0-models/qwen2.5-1.5b-instruct-q4_k_m.gguf"
        const val ZENZ_FILENAME = "zenz-v3.2-small-q5_k_m.gguf"
        const val ZENZ_URL = "https://huggingface.co/Miwa-Keita/zenz-v3.2-small-gguf/resolve/main/ggml-model-Q5_K_M.gguf"
        @Volatile private var autoProvisionStarted = false
    }
}
