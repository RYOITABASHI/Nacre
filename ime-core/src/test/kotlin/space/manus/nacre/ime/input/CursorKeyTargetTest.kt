package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for [computeCursorKeyTarget], the pure caret computation
 * behind the 12-key pad's ◀ / ▶ keys.
 *
 * Regression context: ▶ used to send a raw DPAD_RIGHT key event to the
 * editor. When the caret was already at the end of the text the editor
 * could not consume the event, it escaped to the activity's focus
 * search, and the keyboard hid — "sometimes", exactly when at the end
 * of the text in an app with another focusable widget nearby. The key
 * now moves the caret via setSelection with the target computed here;
 * the end-of-text case below pins the state that used to dismiss the
 * IME: the target simply stays at the end, inside the field.
 */
class CursorKeyTargetTest {

    @Test
    fun `right moves one character mid-text`() {
        assertEquals(3, computeCursorKeyTarget("あいうえお", 2, 2, +1))
    }

    @Test
    fun `left moves one character mid-text`() {
        assertEquals(2, computeCursorKeyTarget("あいうえお", 3, 3, -1))
    }

    @Test
    fun `right at end of text stays at end - the state that used to hide the keyboard`() {
        assertEquals(5, computeCursorKeyTarget("あいうえお", 5, 5, +1))
    }

    @Test
    fun `right in empty field stays at zero`() {
        assertEquals(0, computeCursorKeyTarget("", 0, 0, +1))
    }

    @Test
    fun `left at start of text stays at zero`() {
        assertEquals(0, computeCursorKeyTarget("あいうえお", 0, 0, -1))
    }

    @Test
    fun `right collapses an active selection to its end without moving further`() {
        assertEquals(4, computeCursorKeyTarget("あいうえお", 1, 4, +1))
    }

    @Test
    fun `left collapses an active selection to its start without moving further`() {
        assertEquals(1, computeCursorKeyTarget("あいうえお", 1, 4, -1))
    }

    @Test
    fun `right skips a whole surrogate pair`() {
        // "a😀b": 😀 is U+1F600, two UTF-16 units; text length is 4.
        val text = "a\uD83D\uDE00b"
        assertEquals(3, computeCursorKeyTarget(text, 1, 1, +1))
    }

    @Test
    fun `left skips back over a whole surrogate pair`() {
        val text = "a\uD83D\uDE00b"
        assertEquals(1, computeCursorKeyTarget(text, 3, 3, -1))
    }

    @Test
    fun `reversed selection endpoints collapse to the same edges`() {
        // Anchor after the caret: endpoints arrive as (4, 1) instead of (1, 4).
        assertEquals(4, computeCursorKeyTarget("あいうえお", 4, 1, +1))
        assertEquals(1, computeCursorKeyTarget("あいうえお", 4, 1, -1))
    }

    @Test
    fun `unknown selection yields no target`() {
        assertEquals(-1, computeCursorKeyTarget("あいうえお", -1, -1, +1))
    }
}
