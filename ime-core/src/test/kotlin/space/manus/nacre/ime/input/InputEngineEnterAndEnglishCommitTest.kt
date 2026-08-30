package space.manus.nacre.ime.input

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM-level tests for the pure decision functions backing:
 *  - Enter key behavior (send editor action vs. insert newline vs. raw keycode)
 *  - English composing commit-on-Space/punctuation (stale-candidate race)
 *
 * Both [computeEnterOutcome] and [pickEnglishCommitText] are plain functions
 * over primitives / [ConversionCandidate] (itself plain Kotlin) with no
 * Android object method calls, so — like [space.manus.nacre.ai.PostProcessor]
 * in ime-ai — they run as ordinary JUnit tests without Robolectric or an
 * emulator. Only compile-time Android SDK *constants* (e.g.
 * EditorInfo.IME_ACTION_DONE) are referenced, which is safe under the
 * android.jar unit-test stub (only method bodies throw "Stub!", not field
 * access — javac inlines these constants into this test's bytecode).
 */
class InputEngineEnterAndEnglishCommitTest {

    // ══════════════════════════════════════════════════════
    //  computeEnterOutcome — single-line fields honor the real IME action
    // ══════════════════════════════════════════════════════

    @Test
    fun `single-line field with DONE action performs that action`() {
        val outcome = computeEnterOutcome(
            imeOptions = EditorInfo.IME_ACTION_DONE,
            inputType = InputType.TYPE_CLASS_TEXT,
        )
        assertEquals(EnterOutcome.PerformEditorAction(EditorInfo.IME_ACTION_DONE), outcome)
    }

    @Test
    fun `single-line search box performs SEARCH action`() {
        val outcome = computeEnterOutcome(
            imeOptions = EditorInfo.IME_ACTION_SEARCH,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
        )
        assertEquals(EnterOutcome.PerformEditorAction(EditorInfo.IME_ACTION_SEARCH), outcome)
    }

    @Test
    fun `single-line field with GO NEXT and SEND all perform their action`() {
        for (action in listOf(EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_SEND)) {
            val outcome = computeEnterOutcome(imeOptions = action, inputType = InputType.TYPE_CLASS_TEXT)
            assertEquals(EnterOutcome.PerformEditorAction(action), outcome)
        }
    }

    @Test
    fun `single-line field with no actionable IME option sends raw Enter keycode`() {
        val outcome = computeEnterOutcome(
            imeOptions = EditorInfo.IME_ACTION_UNSPECIFIED,
            inputType = InputType.TYPE_CLASS_TEXT,
        )
        assertEquals(EnterOutcome.SendEnterKey, outcome)
    }

    @Test
    fun `default zero editor info falls back to raw Enter keycode not newline`() {
        // e.g. editorInfo not yet available. Must NOT silently insert a
        // newline into what could be a single-line field.
        val outcome = computeEnterOutcome(imeOptions = 0, inputType = 0)
        assertEquals(EnterOutcome.SendEnterKey, outcome)
    }

    // ══════════════════════════════════════════════════════
    //  computeEnterOutcome — genuinely multi-line fields still get a newline
    // ══════════════════════════════════════════════════════

    @Test
    fun `multiline field with SEND action still inserts newline by default`() {
        // Chat/note compose boxes are flagged multi-line even when they also
        // wire a dedicated send button via IME_ACTION_SEND — this preserves
        // the original "don't accidentally send my Slack message" intent.
        val outcome = computeEnterOutcome(
            imeOptions = EditorInfo.IME_ACTION_SEND,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        )
        assertEquals(EnterOutcome.InsertNewline, outcome)
    }

    @Test
    fun `multiline field with no action inserts newline`() {
        val outcome = computeEnterOutcome(
            imeOptions = 0,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        )
        assertEquals(EnterOutcome.InsertNewline, outcome)
    }

    @Test
    fun `multiline flag on a non-text class is not treated as multiline`() {
        // TYPE_TEXT_FLAG_MULTI_LINE is only meaningful for TYPE_CLASS_TEXT.
        val outcome = computeEnterOutcome(
            imeOptions = EditorInfo.IME_ACTION_DONE,
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        )
        assertEquals(EnterOutcome.PerformEditorAction(EditorInfo.IME_ACTION_DONE), outcome)
    }

    // ══════════════════════════════════════════════════════
    //  computeEnterOutcome — IME_FLAG_NO_ENTER_ACTION
    // ══════════════════════════════════════════════════════

    @Test
    fun `NO_ENTER_ACTION flag suppresses the action on a single-line field`() {
        val outcome = computeEnterOutcome(
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
            inputType = InputType.TYPE_CLASS_TEXT,
        )
        assertEquals(EnterOutcome.SendEnterKey, outcome)
    }

    @Test
    fun `NO_ENTER_ACTION flag is irrelevant on a multiline field`() {
        val outcome = computeEnterOutcome(
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        )
        assertEquals(EnterOutcome.InsertNewline, outcome)
    }

    // ══════════════════════════════════════════════════════
    //  pickEnglishCommitText — stale prediction race on Space/punctuation
    // ══════════════════════════════════════════════════════

    @Test
    fun `fresh top candidate matching the live prefix is committed`() {
        val candidates = listOf(ConversionCandidate(surface = "cat", reading = "cat"))
        assertEquals("cat", pickEnglishCommitText("cat", candidates, selectedIndex = 0))
    }

    @Test
    fun `stale candidate from an earlier shorter prefix is rejected in favor of raw text`() {
        // Simulates: user typed "ca", a 30ms-debounced prediction for "ca" is
        // still in flight/just landed, user then types "t" and immediately
        // hits Space before a fresh prediction for "cat" arrives.
        val staleCandidates = listOf(ConversionCandidate(surface = "car", reading = "ca"))
        assertEquals("cat", pickEnglishCommitText("cat", staleCandidates, selectedIndex = 0))
    }

    @Test
    fun `no candidates yet commits the raw typed text`() {
        assertEquals("cat", pickEnglishCommitText("cat", emptyList(), selectedIndex = -1))
    }

    @Test
    fun `case-insensitive reading match still accepts a fresh spell-correction candidate`() {
        // Spell-correction candidates record a lowercased `reading`, while the
        // live composing buffer preserves the user's actual shift-cased text.
        val candidates = listOf(ConversionCandidate(surface = "Catalog", reading = "cat"))
        assertEquals("Catalog", pickEnglishCommitText("Cat", candidates, selectedIndex = 0))
    }

    @Test
    fun `out-of-range selected index falls back to the first fresh candidate`() {
        val candidates = listOf(ConversionCandidate(surface = "cat", reading = "cat"))
        // selectedIndex left over from a longer candidate list that has since shrunk.
        assertEquals("cat", pickEnglishCommitText("cat", candidates, selectedIndex = 5))
    }

    @Test
    fun `explicitly selected non-first candidate is honored when fresh`() {
        val candidates = listOf(
            ConversionCandidate(surface = "cat", reading = "cat"),
            ConversionCandidate(surface = "cats", reading = "cat"),
        )
        assertEquals("cats", pickEnglishCommitText("cat", candidates, selectedIndex = 1))
    }
}
