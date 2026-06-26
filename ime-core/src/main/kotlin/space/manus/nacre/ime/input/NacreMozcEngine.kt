package space.manus.nacre.ime.input

import android.content.Context
import android.util.Log
import com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Command
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Request
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand
import java.io.File

/**
 * Native Mozc conversion engine (#13). Loads libmozc.so + mozc.data and converts
 * kana → candidates via the Mozc session protocol (CREATE_SESSION → SEND_KEY →
 * read all_candidate_words).
 *
 * Every entry point is defensive: any failure (lib missing, init error, eval throw)
 * returns empty/false so callers fall back to the Kotlin engine. Gated by a setting
 * (default OFF) until validated on-device — the exact key-feeding protocol may need
 * tuning against real evalCommand output.
 */
class NacreMozcEngine(private val context: Context) {

    @Volatile
    private var ready = false
    private var sessionId: Long = 0L

    val isReady: Boolean get() = ready

    @Synchronized
    fun ensureReady(): Boolean {
        if (ready) return true
        return try {
            System.loadLibrary("mozc")
            val dir = File(context.filesDir, "mozc").apply { mkdirs() }
            val dataFile = File(dir, "mozc.data")
            if (!dataFile.exists() || dataFile.length() == 0L) {
                context.assets.open("mozc/mozc.data").use { src ->
                    dataFile.outputStream().use { src.copyTo(it) }
                }
            }
            if (!MozcJNI.onPostLoad(dir.absolutePath, dataFile.absolutePath)) {
                Log.e(TAG, "onPostLoad returned false")
                return false
            }
            val created = eval(Input.newBuilder().setType(Input.CommandType.CREATE_SESSION))
                ?: return false
            sessionId = created.id
            // Android mobile mode → richer prediction/conversion candidates.
            eval(
                Input.newBuilder()
                    .setType(Input.CommandType.SET_REQUEST)
                    .setId(sessionId)
                    .setRequest(Request.newBuilder().setMixedConversion(true)),
            )
            ready = true
            Log.i(TAG, "Mozc ready (dataVersion=${runCatching { MozcJNI.getDataVersion() }.getOrNull()})")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Mozc init failed", e)
            false
        }
    }

    /** Convert a hiragana reading to candidates. Empty on any failure (→ caller falls back). */
    @Synchronized
    fun convert(reading: String): List<ConversionCandidate> {
        if (reading.isEmpty() || !ensureReady()) return emptyList()
        return try {
            sendCommand(SessionCommand.CommandType.REVERT) // clear prior composition
            var out: Output? = null
            for (ch in reading) {
                out = eval(
                    Input.newBuilder()
                        .setType(Input.CommandType.SEND_KEY)
                        .setId(sessionId)
                        .setKey(KeyEvent.newBuilder().setKeyString(ch.toString())),
                )
            }
            val candidates = out?.allCandidateWords?.candidatesList.orEmpty()
                .mapNotNull { it.value?.takeIf(String::isNotEmpty) }
                .distinct()
                .map { ConversionCandidate(surface = it, reading = reading) }
            sendCommand(SessionCommand.CommandType.REVERT) // leave session clean
            candidates
        } catch (e: Throwable) {
            Log.e(TAG, "convert('$reading') failed", e)
            emptyList()
        }
    }

    private fun sendCommand(type: SessionCommand.CommandType) {
        eval(
            Input.newBuilder()
                .setType(Input.CommandType.SEND_COMMAND)
                .setId(sessionId)
                .setCommand(SessionCommand.newBuilder().setType(type)),
        )
    }

    private fun eval(input: Input.Builder): Output? {
        val command = Command.newBuilder().setInput(input.build()).build()
        val bytes = MozcJNI.evalCommand(command.toByteArray()) ?: return null
        return Output.parseFrom(bytes)
    }

    companion object {
        private const val TAG = "NacreMozc"
    }
}
