package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tracked-target state machine behind post-hoc ⇧ on the 12-key ABC
 * grid. These tests simulate the editor honestly: the test holds the
 * span's current text and its absolute end position, anchors the tracker
 * the way the engine does (one extracted-text read right after the
 * commit that starts the span), and feeds each [PostHocShiftTracker]
 * result back as the new span content — exactly what the engine's
 * batched deleteSurroundingText + commitText achieves. Edits the IME
 * did not make are simulated by mutating the span or reporting a
 * different live cursor.
 *
 * Regression context (PR #69): the transform used to target whatever
 * word preceded the cursor and derive each step from its current case
 * pattern, so ⇧ rewrote old words and mangled mixed-case ones. Every
 * test here pins the replacement contract: tracked span only, identity
 * proven by the anchored LIVE position at press time, steps computed
 * from the recorded original, drop on any doubt.
 */
class PostHocShiftTrackerTest {

    private fun typeLetters(tracker: PostHocShiftTracker, word: String) {
        for (ch in word) tracker.onLetterAppended(ch.toString())
    }

    /**
     * Press ⇧ the way the engine does: the span's current text plus the
     * LIVE cursor position (defaults to the anchored end — the cursor
     * sitting exactly where our typing left it).
     */
    private fun pressShift(
        tracker: PostHocShiftTracker,
        span: String,
        liveCursor: Int = tracker.anchorEnd,
    ): String? = tracker.advance(span, liveCursor)

    /** Type [word] and anchor the span's end at [end]. */
    private fun typeAndAnchor(tracker: PostHocShiftTracker, word: String, end: Int) {
        typeLetters(tracker, word)
        tracker.onAnchorKnown(end)
    }

    // ── The basic cycle ────────────────────────────────────────────

    @Test
    fun `typed word cycles from its original and returns to it exactly`() {
        val tracker = PostHocShiftTracker()
        typeAndAnchor(tracker, "hello", end = 100)
        assertTrue(tracker.hasTarget)
        assertEquals("hello", tracker.original)

        var span = "hello"
        span = pressShift(tracker, span)!!
        assertEquals("hellO", span)
        span = pressShift(tracker, span)!!
        assertEquals("Hello", span)
        span = pressShift(tracker, span)!!
        assertEquals("HELLO", span)
        span = pressShift(tracker, span)!!
        assertEquals("hello", span)
        // And it keeps cycling from the same original.
        span = pressShift(tracker, span)!!
        assertEquals("hellO", span)
    }

    @Test
    fun `no target means no transform`() {
        val tracker = PostHocShiftTracker()
        assertFalse(tracker.hasTarget)
        assertNull(pressShift(tracker, "hello", liveCursor = 5))
    }

    @Test
    fun `single letter cycles and returns`() {
        val tracker = PostHocShiftTracker()
        typeAndAnchor(tracker, "a", end = 10)
        var span = "a"
        span = pressShift(tracker, span)!!
        assertEquals("A", span)
        // Capitalize and ALL-CAPS of "a" coincide with the current text;
        // the presses still count and the step still advances.
        span = pressShift(tracker, span)!!
        assertEquals("A", span)
        span = pressShift(tracker, span)!!
        assertEquals("A", span)
        span = pressShift(tracker, span)!!
        assertEquals("a", span)
    }

    // ── Mixed-case originals (the PR #69 failure mode) ─────────────

    @Test
    fun `iPhone typed naturally round-trips to exactly iPhone`() {
        val tracker = PostHocShiftTracker()
        typeAndAnchor(tracker, "iPhone", end = 20)
        var span = "iPhone"
        span = pressShift(tracker, span)!!
        assertEquals("iPhonE", span)
        span = pressShift(tracker, span)!!
        assertEquals("Iphone", span)
        span = pressShift(tracker, span)!!
        assertEquals("IPHONE", span)
        span = pressShift(tracker, span)!!
        // PR #69 could never restore this — its pattern derivation had
        // already normalized the word away.
        assertEquals("iPhone", span)
    }

    @Test
    fun `McDonald round-trips to exactly McDonald`() {
        val tracker = PostHocShiftTracker()
        typeAndAnchor(tracker, "McDonald", end = 30)
        var span = "McDonald"
        span = pressShift(tracker, span)!!
        assertEquals("McDonalD", span)
        span = pressShift(tracker, span)!!
        assertEquals("Mcdonald", span)
        span = pressShift(tracker, span)!!
        assertEquals("MCDONALD", span)
        span = pressShift(tracker, span)!!
        assertEquals("McDonald", span)
    }

    // ── Identity: fail closed (review rounds 1+2) ──────────────────

    @Test
    fun `live cursor behind an identical older letter drops the target`() {
        // The scenario content equality cannot catch: the user typed
        // "a", then tapped the cursor right behind an older "a". The
        // selection notification may not even have arrived yet — the
        // LIVE position read at ⇧ time is what authorizes, and it is
        // not our anchor.
        val tracker = PostHocShiftTracker()
        typeAndAnchor(tracker, "a", end = 5)
        assertNull(pressShift(tracker, "a", liveCursor = 42))
        assertFalse(tracker.hasTarget)
    }

    @Test
    fun `unanchored target fails closed and is dropped`() {
        // The editor could not report its text when the span started,
        // so no anchor exists. The target must never be rewritten —
        // identity is unprovable — even with matching text and a
        // plausible-looking live cursor.
        val tracker = PostHocShiftTracker()
        typeLetters(tracker, "hello")
        assertEquals(-1, tracker.anchorEnd)
        assertNull(pressShift(tracker, "hello", liveCursor = 5))
        assertFalse(tracker.hasTarget)
    }

    @Test
    fun `external edit inside the span drops the target instead of guessing`() {
        val tracker = PostHocShiftTracker()
        typeAndAnchor(tracker, "hello", end = 100)
        var span = pressShift(tracker, "hello")!!
        assertEquals("hellO", span)

        // The app (or an unobserved edit) changed the text in place.
        span = "hellX"
        assertNull(pressShift(tracker, span))
        assertFalse(tracker.hasTarget)
        // And it stays dropped — the old text coming back proves nothing.
        assertNull(pressShift(tracker, "hellO"))
    }

    @Test
    fun `mismatch before the first transform also drops`() {
        val tracker = PostHocShiftTracker()
        typeAndAnchor(tracker, "hello", end = 100)
        assertNull(pressShift(tracker, "hxllo"))
        assertFalse(tracker.hasTarget)
    }

    @Test
    fun `span with no cased letter is dropped, not spun on`() {
        val tracker = PostHocShiftTracker()
        // The engine never appends uncased letters, but the tracker must
        // not offer a no-op cycle if one ever lands here.
        tracker.onLetterAppended("あ")
        tracker.onAnchorKnown(3)
        assertNull(pressShift(tracker, "あ"))
        assertFalse(tracker.hasTarget)
    }

    // ── Reset ──────────────────────────────────────────────────────

    @Test
    fun `reset kills the target`() {
        val tracker = PostHocShiftTracker()
        typeAndAnchor(tracker, "hello", end = 100)
        tracker.reset()
        assertFalse(tracker.hasTarget)
        assertNull(pressShift(tracker, "hello", liveCursor = 100))
    }

    @Test
    fun `typing after a reset starts a fresh target`() {
        val tracker = PostHocShiftTracker()
        typeAndAnchor(tracker, "hello", end = 100)
        tracker.reset()
        typeAndAnchor(tracker, "ab", end = 50)
        assertEquals("ab", tracker.original)
        assertEquals("aB", pressShift(tracker, "ab"))
    }

    // ── Continued typing ───────────────────────────────────────────

    @Test
    fun `typing more letters after a transform re-baselines the original`() {
        val tracker = PostHocShiftTracker()
        typeAndAnchor(tracker, "hel", end = 103)
        var span = pressShift(tracker, "hel")!!
        assertEquals("heL", span)

        // The user keeps typing: the span as it stands ("heL") plus the
        // new letter becomes the new original, cycle back at step 0 —
        // and the anchor advances with the commit.
        tracker.onLetterAppended("o")
        assertEquals("heLo", tracker.original)
        assertEquals(ShiftCaseCycle.STEP_ORIGINAL, tracker.step)
        assertEquals(104, tracker.anchorEnd)

        span = "heLo"
        span = pressShift(tracker, span)!!
        assertEquals("heLO", span)
        span = pressShift(tracker, span)!!
        assertEquals("Helo", span)
        span = pressShift(tracker, span)!!
        assertEquals("HELO", span)
        span = pressShift(tracker, span)!!
        assertEquals("heLo", span)
    }

    // ── Tap-cycle replacement ──────────────────────────────────────

    @Test
    fun `tap-cycle replacement rewrites the original's last letter`() {
        val tracker = PostHocShiftTracker()
        typeAndAnchor(tracker, "ab", end = 60)
        tracker.onLastLetterReplaced("c")
        assertEquals("ac", tracker.original)
        assertEquals("aC", pressShift(tracker, "ac"))
    }

    @Test
    fun `replacement with no target starts a one-letter target`() {
        val tracker = PostHocShiftTracker()
        tracker.onLastLetterReplaced("b")
        assertEquals("b", tracker.original)
        // The engine anchors right after the replacement commit.
        tracker.onAnchorKnown(7)
        assertEquals("B", pressShift(tracker, "b"))
    }

    @Test
    fun `replacement preserves the committed case in the original`() {
        val tracker = PostHocShiftTracker()
        // Pre-armed ⇧ produced "A"; cycling the tap replaces it with "B"
        // (the engine's replace path keeps the replaced char's case).
        typeAndAnchor(tracker, "A", end = 1)
        tracker.onLastLetterReplaced("B")
        assertEquals("B", tracker.original)
        var span = "B"
        span = pressShift(tracker, span)!!
        assertEquals("B", span) // last-char-upper of "B" is "B"
        span = pressShift(tracker, span)!!
        assertEquals("B", span) // Capitalize of "B" is "B"
        span = pressShift(tracker, span)!!
        assertEquals("B", span) // ALL-CAPS of "B" is "B"
        span = pressShift(tracker, span)!!
        assertEquals("B", span) // back to the original — which IS "B"
        assertEquals(ShiftCaseCycle.STEP_ORIGINAL, tracker.step)
    }

    // ── Absolute anchor + external selection changes ───────────────

    @Test
    fun `anchor is captured once and maintained arithmetically`() {
        val tracker = PostHocShiftTracker()
        tracker.onLetterAppended("h")
        // The engine reads the position from the editor once, right
        // after the commit that started the span: cursor sits at 101.
        tracker.onAnchorKnown(101)
        assertEquals(101, tracker.anchorEnd)

        typeLetters(tracker, "ello")
        assertEquals(105, tracker.anchorEnd)

        // Replacement and case rewrites are length-preserving.
        tracker.onLastLetterReplaced("p")
        assertEquals(105, tracker.anchorEnd)
        assertEquals("hellP", pressShift(tracker, "hellp"))
        assertEquals(105, tracker.anchorEnd)
    }

    @Test
    fun `selection update at the anchored end is our own echo and keeps the target`() {
        val tracker = PostHocShiftTracker()
        typeAndAnchor(tracker, "hello", end = 105)
        tracker.onSelectionChanged(105, 105)
        assertTrue(tracker.hasTarget)
        assertEquals("hellO", pressShift(tracker, "hello"))
    }

    @Test
    fun `cursor tapped elsewhere drops the target via the selection update`() {
        val tracker = PostHocShiftTracker()
        typeAndAnchor(tracker, "a", end = 5)
        tracker.onSelectionChanged(42, 42)
        assertFalse(tracker.hasTarget)
        assertNull(pressShift(tracker, "a", liveCursor = 42))
    }

    @Test
    fun `a drag selection drops the target`() {
        val tracker = PostHocShiftTracker()
        typeAndAnchor(tracker, "hello", end = 105)
        tracker.onSelectionChanged(103, 105)
        assertFalse(tracker.hasTarget)
    }

    @Test
    fun `an unanchored target is dropped by any selection update`() {
        val tracker = PostHocShiftTracker()
        typeLetters(tracker, "hello")
        // No anchor: our own commit's echo cannot be told from an
        // external move, so any update drops the target. Letting it
        // linger would let later letters extend a span whose first
        // letters may no longer sit before the cursor — and a late
        // anchor capture must never launder that into a target.
        tracker.onSelectionChanged(99, 99)
        assertFalse(tracker.hasTarget)
    }

    @Test
    fun `anchor is ignored without a target and cleared by reset`() {
        val tracker = PostHocShiftTracker()
        tracker.onAnchorKnown(10)
        assertEquals(-1, tracker.anchorEnd)

        typeAndAnchor(tracker, "ab", end = 12)
        assertEquals(12, tracker.anchorEnd)
        tracker.reset()
        assertEquals(-1, tracker.anchorEnd)
    }
}
