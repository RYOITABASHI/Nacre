package space.manus.nacre.ime.input

import android.content.Context
import android.util.Log
import com.google.android.apps.inputmethod.libs.mozc.session.MozcJNI
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Command
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.CompositionMode
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Input
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.KeyEvent
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Output
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.Request
import org.mozc.android.inputmethod.japanese.protobuf.ProtoCommands.SessionCommand
import java.io.File

/**
 * Native Mozc conversion engine (#13). Loads libmozc.so + mozc.data and converts a
 * reading → candidates via the Mozc session protocol.
 *
 * Protocol (verified against Mozc source / session scenario tests):
 *  - init: System.loadLibrary → initialize() (RegisterNatives) → onPostLoad(profile, data)
 *    → CREATE_SESSION → SET_REQUEST(full mobile request) → SEND_KEY(ON).
 *  - convert: RESET_CONTEXT, then for each char SEND_KEY{key_code=ASCII} (romaji table
 *    composes kana) or SEND_KEY{key_string + input_style=AS_IS} for a literal kana char,
 *    then SEND_KEY{SPACE} to convert, and read Output.all_candidate_words.
 *
 * Every entry point is defensive: any failure returns empty so callers fall back to the
 * Kotlin engine. Gated by a setting (default OFF).
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
            // libmozc.so has no JNI_OnLoad — initialize() is the only name-mangled entry
            // and it RegisterNatives the rest. Must run before onPostLoad.
            if (!MozcJNI.initialize()) {
                Log.e(TAG, "MozcJNI.initialize() failed")
                return false
            }
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
            sessionId = eval(Input.newBuilder().setType(Input.CommandType.CREATE_SESSION))?.id
                ?: return false
            // Full mobile request — mixed_conversion alone is NOT enough to get candidates;
            // the romaji table (special_romanji_table) + the bundle are required.
            eval(
                Input.newBuilder()
                    .setType(Input.CommandType.SET_REQUEST)
                    .setId(sessionId)
                    .setRequest(
                        Request.newBuilder()
                            .setSpecialRomanjiTable(Request.SpecialRomanjiTable.QWERTY_MOBILE_TO_HIRAGANA)
                            .setMixedConversion(true)
                            .setZeroQuerySuggestion(true)
                            .setUpdateInputModeFromSurroundingText(false)
                            .setKanaModifierInsensitiveConversion(true)
                            .setAutoPartialSuggestion(true)
                            .setLanguageAwareInput(Request.LanguageAwareInputBehavior.NO_LANGUAGE_AWARE_INPUT),
                    ),
            )
            // Ensure the IME is on + HIRAGANA (matches predict_and_convert.txt).
            eval(
                Input.newBuilder()
                    .setType(Input.CommandType.SEND_KEY)
                    .setId(sessionId)
                    .setKey(
                        KeyEvent.newBuilder()
                            .setSpecialKey(KeyEvent.SpecialKey.ON)
                            .setMode(CompositionMode.HIRAGANA),
                    ),
            )
            ready = true
            Log.i(TAG, "Mozc ready (dataVersion=${runCatching { MozcJNI.getDataVersion() }.getOrNull()})")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Mozc init failed", e)
            // N1: a session may have been created before a later step threw — delete it
            // so retries don't leak native sessions.
            if (sessionId != 0L) {
                runCatching {
                    eval(Input.newBuilder().setType(Input.CommandType.DELETE_SESSION).setId(sessionId))
                }
                sessionId = 0L
            }
            false
        }
    }

    /**
     * Convert [input] to candidates. Prefer the raw romaji buffer (ASCII, composed by the
     * romaji table); falls back to literal kana via AS_IS. Empty on any failure.
     */
    @Synchronized
    fun convert(input: String): List<ConversionCandidate> {
        if (input.isEmpty() || !ensureReady()) return emptyList()
        return try {
            sendCommand(SessionCommand.CommandType.RESET_CONTEXT)
            // Re-assert HIRAGANA after reset (every passing scenario does this).
            eval(
                Input.newBuilder()
                    .setType(Input.CommandType.SEND_COMMAND)
                    .setId(sessionId)
                    .setCommand(
                        SessionCommand.newBuilder()
                            .setType(SessionCommand.CommandType.SWITCH_COMPOSITION_MODE)
                            .setCompositionMode(CompositionMode.HIRAGANA),
                    ),
            )
            var out: Output? = null
            for (ch in input) {
                val key = KeyEvent.newBuilder()
                if (ch.code < 0x80) {
                    // ASCII (romaji) → key_code; the romaji table composes it into kana.
                    key.setKeyCode(ch.code)
                } else {
                    // Literal kana → insert as-is (bypasses the romaji table).
                    key.setKeyString(ch.toString()).setInputStyle(KeyEvent.InputStyle.AS_IS)
                }
                out = eval(
                    Input.newBuilder()
                        .setType(Input.CommandType.SEND_KEY)
                        .setId(sessionId)
                        .setKey(key),
                )
            }
            val preedit = out?.preedit?.segmentList.orEmpty().joinToString("") { it.value }
            // SPACE converts → full candidate list lands in all_candidate_words.
            val outSp = eval(
                Input.newBuilder()
                    .setType(Input.CommandType.SEND_KEY)
                    .setId(sessionId)
                    .setKey(KeyEvent.newBuilder().setSpecialKey(KeyEvent.SpecialKey.SPACE)),
            )
            val all = outSp?.allCandidateWords?.candidatesList.orEmpty().map { it.value }
            val win = outSp?.candidateWindow?.candidateList.orEmpty().map { it.value }
            val values = (all + win).filter { it.isNotEmpty() }.distinct()
            Log.i(TAG, "convert('$input'): preedit='$preedit' all=${all.size} win=${win.size} → ${values.take(3)}")
            sendCommand(SessionCommand.CommandType.RESET_CONTEXT)
            values.map { ConversionCandidate(surface = it, reading = input) }
        } catch (e: Throwable) {
            Log.e(TAG, "convert('$input') failed", e)
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
