package space.manus.nacre.ai

import android.content.Context
import java.io.File

/**
 * Cross-process status of voice input (ASR + refinement), mirroring
 * [ZenzStatus]. Written by the IME process (VoiceInputManager), readable
 * from Settings or diagnostics.
 *
 * This exists because voice input had two silent-degradation paths: a
 * missing on-device ASR model silently fell back to Android
 * SpeechRecognizer (possibly network recognition, despite the offline
 * expectation), and a local LLM load failure silently disabled refinement
 * for the whole session. Every such transition is recorded here with its
 * reason, and the keyboard's voice panel shows the same states live.
 *
 * File format (single values, one per line):
 *   asrState / asrDetail / refineState / refineDetail / epochMillis
 */
object VoiceStatus {

    enum class AsrState {
        /** On-device (sherpa-onnx) model loaded and in use. */
        READY,
        /** Model exists but is still loading. */
        LOADING,
        /** ASR model files not present on the device. */
        NOT_DOWNLOADED,
        /** Model present but failed to load. */
        LOAD_ERROR,
        /** Android SpeechRecognizer is substituting for the on-device model. */
        EXTERNAL_FALLBACK,
        /** Recognition failed at runtime (service error). */
        ERROR,
    }

    enum class RefineState {
        /** Local Qwen (LlmService) loaded and refining. */
        READY_LOCAL,
        /** A cloud refiner is configured and handles refinement. */
        READY_CLOUD,
        /** LLM still loading / not yet attempted. */
        LOADING,
        /** No refiner available; dictation stays at the deterministic cleanup. */
        UNAVAILABLE,
    }

    data class Snapshot(
        val asrState: AsrState?,
        val asrDetail: String,
        val refineState: RefineState?,
        val refineDetail: String,
        val epochMillis: Long,
    )

    private const val STATUS_FILE = "voice_status.txt"

    @Synchronized
    fun recordAsr(context: Context, state: AsrState, detail: String = "") {
        val cur = read(context)
        write(
            context,
            Snapshot(
                asrState = state,
                asrDetail = detail,
                refineState = cur?.refineState,
                refineDetail = cur?.refineDetail ?: "",
                epochMillis = System.currentTimeMillis(),
            ),
        )
    }

    @Synchronized
    fun recordRefine(context: Context, state: RefineState, detail: String = "") {
        val cur = read(context)
        write(
            context,
            Snapshot(
                asrState = cur?.asrState,
                asrDetail = cur?.asrDetail ?: "",
                refineState = state,
                refineDetail = detail,
                epochMillis = System.currentTimeMillis(),
            ),
        )
    }

    private fun write(context: Context, snapshot: Snapshot) {
        try {
            val file = File(context.filesDir, STATUS_FILE)
            fun safe(s: String) = s.replace('\n', ' ').take(300)
            file.writeText(
                "${snapshot.asrState?.name ?: ""}\n" +
                    "${safe(snapshot.asrDetail)}\n" +
                    "${snapshot.refineState?.name ?: ""}\n" +
                    "${safe(snapshot.refineDetail)}\n" +
                    "${snapshot.epochMillis}\n",
            )
        } catch (_: Exception) {
            // Status recording must never break voice input.
        }
    }

    fun read(context: Context): Snapshot? {
        return try {
            val file = File(context.filesDir, STATUS_FILE)
            if (!file.exists()) return null
            val lines = file.readLines()
            if (lines.size < 5) return null
            Snapshot(
                asrState = lines[0].takeIf { it.isNotBlank() }
                    ?.let { runCatching { AsrState.valueOf(it) }.getOrNull() },
                asrDetail = lines[1],
                refineState = lines[2].takeIf { it.isNotBlank() }
                    ?.let { runCatching { RefineState.valueOf(it) }.getOrNull() },
                refineDetail = lines[3],
                epochMillis = lines[4].toLongOrNull() ?: 0L,
            )
        } catch (_: Exception) {
            null
        }
    }
}
