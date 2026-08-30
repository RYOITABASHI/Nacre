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
            val created = eval(Input.newBuilder().setType(Input.CommandType.CREATE_SESSION))
                ?: return false
            sessionId = created.id
            Log.i(TAG, "CREATE_SESSION: id=$sessionId err=${created.errorCode}")
            // N2: a failed CREATE_SESSION (error_code=SESSION_FAILURE, default id=0) must not
            // be treated as ready — every later eval() would silently no-op against a dead
            // session id, and callers would keep paying the JNI round-trip cost forever while
            // getting empty results (indistinguishable from "Mozc has no candidates").
            if (created.errorCode != Output.ErrorCode.SESSION_SUCCESS || sessionId == 0L) {
                Log.e(TAG, "CREATE_SESSION failed: err=${created.errorCode} id=$sessionId")
                sessionId = 0L
                return false
            }
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
            val outSp = composeAndConvert(input)
            val values = fullLengthCandidateValues(outSp)
            sendCommand(SessionCommand.CommandType.RESET_CONTEXT)
            values.map { ConversionCandidate(surface = it, reading = input) }
        } catch (e: Throwable) {
            Log.e(TAG, "convert('$input') failed", e)
            emptyList()
        }
    }

    /**
     * After CONVERT, `preedit` holds EVERY bunsetsu segment of the reading
     * (記者の｜記者が｜汽車で｜帰社した) while `all_candidate_words`/`candidate_window` hold
     * alternatives for the FOCUSED (first) segment only. The candidate bar commits a candidate
     * wholesale — replacing the *entire* composing reading — so a bare first-segment candidate
     * would silently drop everything after it (bug: 変換候補を確定すると以降の文が消える, e.g.
     * "しょうでんりょくもーどになってた" → selecting "省電力モード" lost "になってた" entirely).
     * Rebuild each candidate as (first-segment alternative + the already-converted remaining
     * segments) so every candidate is a full-length conversion of the whole reading and is safe
     * to commit as-is — matches the pre-Mozc Kotlin engine's full-sentence candidate behavior.
     */
    private fun fullLengthCandidateValues(output: Output?): List<String> {
        val segs = output?.preedit?.segmentList.orEmpty().map { it.value }
        val rest = restAfterFocusedSegment(segs)
        val fullConversion = segs.joinToString("")
        val firstAlts = (
            output?.allCandidateWords?.candidatesList.orEmpty().map { it.value } +
                output?.candidateWindow?.candidateList.orEmpty().map { it.value }
            ).filter { it.isNotEmpty() }
        return buildList {
            if (fullConversion.isNotEmpty()) add(fullConversion)
            firstAlts.forEach { alt -> add(alt + rest) }
        }.filter { it.isNotEmpty() }.distinct()
    }

    /**
     * The already-converted text of every segment AFTER the focused one — the "rest" that
     * [fullLengthCandidateValues] appends to a bare first-segment alternative, and that
     * [learn] strips back off [surface] before matching it against Mozc's candidate list.
     * Normally the focused segment is `segs[0]`, but this defends against an empty leading
     * segment (e.g. a cursor-position placeholder) by using the first non-empty one instead —
     * otherwise `rest` would swallow the real focused segment's own text too.
     */
    private fun restAfterFocusedSegment(segs: List<String>): String {
        val focusedIndex = segs.indexOfFirst { it.isNotEmpty() }.let { if (it < 0) 0 else it }
        return if (focusedIndex in segs.indices) segs.drop(focusedIndex + 1).joinToString("") else ""
    }

    /**
     * #13/M5: tell Mozc which candidate the user actually committed for [reading], so its own
     * UserHistoryPredictor / segment-history-rewriter learns from real usage — mirroring what
     * the mobile_apply_user_segment_history_rewriter.txt scenario in the Mozc source does:
     * convert, then SessionCommand.SUBMIT_CANDIDATE with the chosen candidate's id.
     *
     * Without this, every convert()/predict() call is immediately followed by RESET_CONTEXT
     * with no commit ever reaching the session, so Mozc's own learning never fires no matter
     * what the user picks in Nacre's UI — history stays perpetually cold.
     *
     * Re-derives the candidate list for [reading] rather than reusing a cached one, so it does
     * not need to track cross-call state with predict()/convert() (which may race on other
     * threads); this call is fire-and-forget from the caller's perspective and safe to skip
     * (no-op) if Mozc doesn't offer [surface] for [reading] — e.g. the candidate came from the
     * legacy Kotlin engine instead. Must be called off the main thread (same JNI-per-char cost
     * as convert()/predict()).
     *
     * [surface] is the full-length string convert()/[fullLengthCandidateValues] actually
     * committed — i.e. (first-segment alternative + already-converted remaining segments), not
     * a bare Mozc candidate value. Strip the shared `rest` suffix back off before matching
     * against Mozc's own first-segment-only candidate list, or every multi-segment commit would
     * silently fail to match anything and never teach Mozc.
     */
    @Synchronized
    fun learn(reading: String, surface: String) {
        if (reading.isEmpty() || surface.isEmpty() || !ensureReady()) return
        try {
            val outSp = composeAndConvert(reading)
            val segs = outSp?.preedit?.segmentList.orEmpty().map { it.value }
            val rest = restAfterFocusedSegment(segs)
            val firstSegmentSurface = if (rest.isNotEmpty() && surface.endsWith(rest)) {
                surface.removeSuffix(rest)
            } else {
                surface
            }
            val candidateId = findCandidateId(outSp, firstSegmentSurface)
            if (candidateId != null) {
                eval(
                    Input.newBuilder()
                        .setType(Input.CommandType.SEND_COMMAND)
                        .setId(sessionId)
                        .setCommand(
                            SessionCommand.newBuilder()
                                .setType(SessionCommand.CommandType.SUBMIT_CANDIDATE)
                                .setId(candidateId),
                        ),
                )
                Log.i(TAG, "learn: submitted candidate id=$candidateId for '$reading'->'$surface'")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "learn('$reading'->'$surface') failed", e)
        } finally {
            // Always leave the session clean for the next predict()/convert() call, whether or
            // not a matching candidate was found/submitted.
            runCatching { sendCommand(SessionCommand.CommandType.RESET_CONTEXT) }
        }
    }

    private fun findCandidateId(output: Output?, surface: String): Int? {
        if (output == null) return null
        output.allCandidateWords?.candidatesList.orEmpty()
            .firstOrNull { it.value == surface }
            ?.let { return it.id }
        output.candidateWindow?.candidateList.orEmpty()
            .firstOrNull { it.value == surface }
            ?.let { return it.id }
        return null
    }

    /**
     * RESET_CONTEXT, reassert HIRAGANA, compose [input] char-by-char, then SEND_KEY(SPACE) to
     * convert. Shared by convert() and learn() so both stay in lockstep with the exact
     * composition sequence verified against Mozc's predict_and_convert.txt scenario. Leaves the
     * session in CONVERSION state (candidates focused) — callers are responsible for the
     * follow-up RESET_CONTEXT (convert()) or SUBMIT_CANDIDATE+RESET_CONTEXT (learn()).
     */
    private fun composeAndConvert(input: String): Output? {
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
        for (ch in input) {
            val key = KeyEvent.newBuilder()
            if (ch.code < 0x80) {
                // ASCII (romaji) → key_code; the romaji table composes it into kana.
                key.setKeyCode(ch.code)
            } else {
                // Literal kana → insert as-is (bypasses the romaji table).
                key.setKeyString(ch.toString()).setInputStyle(KeyEvent.InputStyle.AS_IS)
            }
            eval(
                Input.newBuilder()
                    .setType(Input.CommandType.SEND_KEY)
                    .setId(sessionId)
                    .setKey(key),
            )
        }
        // SPACE converts → full candidate list lands in all_candidate_words.
        return eval(
            Input.newBuilder()
                .setType(Input.CommandType.SEND_KEY)
                .setId(sessionId)
                .setKey(KeyEvent.newBuilder().setSpecialKey(KeyEvent.SpecialKey.SPACE)),
        )
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
        // Mozc JNI returns the full Command (with `output` filled), NOT a bare Output.
        // Parsing the bytes as Output silently drops everything (wrong wire layout) →
        // empty preedit / 0 candidates / sessionId=0. Read Command.output instead.
        val response = Command.parseFrom(bytes)
        return if (response.hasOutput()) response.output else null
    }

    companion object {
        private const val TAG = "NacreMozc"
    }
}
