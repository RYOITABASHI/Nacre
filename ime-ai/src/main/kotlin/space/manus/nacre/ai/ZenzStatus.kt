package space.manus.nacre.ai

import android.content.Context
import java.io.File

/**
 * Cross-process status of the zenz neural converter, shown in Settings.
 *
 * The zenz model lives in the :llm process (LlmService), the conversion
 * client lives in the IME process, and the reader is the Settings activity —
 * three processes, so SharedPreferences (unreliable cross-process) is not
 * used. Instead the writer appends the latest state to a small file in
 * filesDir and readers read it fresh every time.
 *
 * This exists because a silently-dead model is a known failure mode in this
 * project (a previous cleanup model never loaded and nothing said so): every
 * zenz load/generation failure is recorded here with its detail.
 */
object ZenzStatus {

    const val PREFS_NAME = "nacre_zenz"
    const val KEY_ENABLED = "enabled"

    enum class State {
        NOT_DOWNLOADED,
        DOWNLOADING,
        READY,
        LOADING,
        LOAD_ERROR,
        GENERATION_ERROR,
        DISABLED,
    }

    data class Snapshot(
        val state: State,
        val detail: String,
        val epochMillis: Long,
    )

    private const val STATUS_FILE = "zenz_status.txt"

    /** Settings toggle: default ON (effective only when the model is Ready). */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun record(context: Context, state: State, detail: String = "") {
        try {
            val file = File(context.filesDir, STATUS_FILE)
            // Detail may contain newlines from exception messages; keep the
            // file single-line so parsing stays trivial.
            val safeDetail = detail.replace('\n', ' ').take(300)
            file.writeText("${state.name}\n$safeDetail\n${System.currentTimeMillis()}\n")
        } catch (_: Exception) {
            // Status recording must never break conversion.
        }
    }

    fun read(context: Context): Snapshot? {
        return try {
            val file = File(context.filesDir, STATUS_FILE)
            if (!file.exists()) return null
            val lines = file.readLines()
            if (lines.size < 3) return null
            Snapshot(
                state = State.valueOf(lines[0]),
                detail = lines[1],
                epochMillis = lines[2].toLongOrNull() ?: 0L,
            )
        } catch (_: Exception) {
            null
        }
    }
}
