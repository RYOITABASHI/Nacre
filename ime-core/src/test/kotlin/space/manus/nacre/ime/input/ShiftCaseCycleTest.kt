package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Post-hoc Shift capitalization on the 12-key ABC grid (⇧ pressed AFTER
 * typing). The engine seals any pending multi-tap state first and then
 * applies [ShiftCaseCycle.decide] to the committed word before the cursor;
 * because the cycle's first step uppercases exactly the LAST character,
 * the letter the user just tapped (and sealed) is the one uppercased.
 * The cycle itself is stateless — derived from the word's current case
 * pattern — so these tests walk it purely through repeated transforms.
 */
class ShiftCaseCycleTest {

    // ── wordBeforeCursor ───────────────────────────────────────────

    @Test
    fun `word is the trailing letter run`() {
        assertEquals("bar", ShiftCaseCycle.wordBeforeCursor("foo bar"))
        assertEquals("hello", ShiftCaseCycle.wordBeforeCursor("hello"))
        assertEquals("c", ShiftCaseCycle.wordBeforeCursor("ab1c"))
    }

    @Test
    fun `no word when the char before the cursor is not a letter`() {
        assertEquals("", ShiftCaseCycle.wordBeforeCursor(""))
        assertEquals("", ShiftCaseCycle.wordBeforeCursor("foo "))
        assertEquals("", ShiftCaseCycle.wordBeforeCursor("foo1"))
        assertEquals("", ShiftCaseCycle.wordBeforeCursor("foo."))
    }

    // ── The cycle ──────────────────────────────────────────────────

    @Test
    fun `full cycle - last char, Capitalize, ALL-CAPS, lowercase`() {
        var word = "hello"
        word = ShiftCaseCycle.nextTransform(word)!!
        assertEquals("hellO", word)
        word = ShiftCaseCycle.nextTransform(word)!!
        assertEquals("Hello", word)
        word = ShiftCaseCycle.nextTransform(word)!!
        assertEquals("HELLO", word)
        word = ShiftCaseCycle.nextTransform(word)!!
        assertEquals("hello", word)
        // And it keeps cycling.
        assertEquals("hellO", ShiftCaseCycle.nextTransform(word))
    }

    @Test
    fun `first press uppercases the last character only`() {
        assertEquals("aB", ShiftCaseCycle.nextTransform("ab"))
        assertEquals("nacrE", ShiftCaseCycle.nextTransform("nacre"))
    }

    @Test
    fun `single letter is a plain toggle`() {
        assertEquals("A", ShiftCaseCycle.nextTransform("a"))
        assertEquals("a", ShiftCaseCycle.nextTransform("A"))
    }

    @Test
    fun `two letter word walks every step without dead presses`() {
        var word = "ab"
        word = ShiftCaseCycle.nextTransform(word)!!
        assertEquals("aB", word)
        word = ShiftCaseCycle.nextTransform(word)!!
        assertEquals("Ab", word)
        word = ShiftCaseCycle.nextTransform(word)!!
        assertEquals("AB", word)
        word = ShiftCaseCycle.nextTransform(word)!!
        assertEquals("ab", word)
    }

    @Test
    fun `already Capitalized word goes to ALL-CAPS then lowercase`() {
        // e.g. typed with classic pre-arm for the first letter
        assertEquals("NACRE", ShiftCaseCycle.nextTransform("Nacre"))
        assertEquals("nacre", ShiftCaseCycle.nextTransform("NACRE"))
    }

    @Test
    fun `mixed case normalizes to Capitalized instead of getting stuck`() {
        assertEquals("Hello", ShiftCaseCycle.nextTransform("hELLO"))
    }

    @Test
    fun `word without cased letters has no transform`() {
        assertNull(ShiftCaseCycle.nextTransform(""))
        assertNull(ShiftCaseCycle.nextTransform("あ"))
        assertNull(ShiftCaseCycle.nextTransform("あいう"))
    }

    @Test
    fun `uncased kana inside the run is skipped, never the cycle target`() {
        // Kana typed just before switching to the ABC grid: uppercasing the
        // trailing kana would be a no-op that eats every ⇧ press. The
        // cycle acts on the cased letters and still advances fully.
        var word = "abcあ"
        word = ShiftCaseCycle.nextTransform(word)!!
        assertEquals("abCあ", word)
        word = ShiftCaseCycle.nextTransform(word)!!
        assertEquals("Abcあ", word)
        word = ShiftCaseCycle.nextTransform(word)!!
        assertEquals("ABCあ", word)
        word = ShiftCaseCycle.nextTransform(word)!!
        assertEquals("abcあ", word)
        // Leading kana behaves the same way.
        assertEquals("あA", ShiftCaseCycle.nextTransform("あa"))
        assertEquals("あa", ShiftCaseCycle.nextTransform("あA"))
    }

    // ── decide (engine entry) ──────────────────────────────────────

    @Test
    fun `decide transforms the word before the cursor`() {
        assertEquals("baR", ShiftCaseCycle.decide("foo bar"))
        assertEquals("B", ShiftCaseCycle.decide("foo b"))
        assertEquals("hellO", ShiftCaseCycle.decide("hello"))
        // The just-tapped letter after a sentence break is the target.
        assertEquals("I", ShiftCaseCycle.decide("done. i"))
    }

    @Test
    fun `decide falls back to pre-arm at non-letter boundaries`() {
        assertNull(ShiftCaseCycle.decide(""))
        assertNull(ShiftCaseCycle.decide("foo "))
        assertNull(ShiftCaseCycle.decide("foo1"))
        assertNull(ShiftCaseCycle.decide("foo."))
        // A kana word has letters but no case: still a fallback.
        assertNull(ShiftCaseCycle.decide("これは"))
    }
}
