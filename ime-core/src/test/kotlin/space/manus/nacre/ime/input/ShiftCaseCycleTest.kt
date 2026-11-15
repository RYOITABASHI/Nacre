package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The case forms of the post-hoc Shift cycle. Every form is computed
 * from the span's recorded ORIGINAL via [ShiftCaseCycle.formAt] — never
 * re-derived from the current text (that was PR #69's design, and the
 * reason natural mixed-case words were mangled). The state machine that
 * decides when a form may be applied lives in [PostHocShiftTracker]
 * and is covered by PostHocShiftTrackerTest.
 */
class ShiftCaseCycleTest {

    @Test
    fun `forms of a lowercase original walk the full cycle`() {
        val original = "hello"
        assertEquals("hello", ShiftCaseCycle.formAt(original, ShiftCaseCycle.STEP_ORIGINAL))
        assertEquals("hellO", ShiftCaseCycle.formAt(original, ShiftCaseCycle.STEP_LAST_CHAR_UPPER))
        assertEquals("Hello", ShiftCaseCycle.formAt(original, ShiftCaseCycle.STEP_CAPITALIZED))
        assertEquals("HELLO", ShiftCaseCycle.formAt(original, ShiftCaseCycle.STEP_ALL_CAPS))
    }

    @Test
    fun `step wraps modulo the cycle length`() {
        assertEquals("hello", ShiftCaseCycle.formAt("hello", ShiftCaseCycle.STEP_COUNT))
        assertEquals("hellO", ShiftCaseCycle.formAt("hello", ShiftCaseCycle.STEP_COUNT + 1))
    }

    @Test
    fun `last-char-upper touches only the last cased letter`() {
        assertEquals("aB", ShiftCaseCycle.formAt("ab", ShiftCaseCycle.STEP_LAST_CHAR_UPPER))
        assertEquals("nacrE", ShiftCaseCycle.formAt("nacre", ShiftCaseCycle.STEP_LAST_CHAR_UPPER))
        // Earlier capitals in the original are left exactly as typed.
        assertEquals("McDonalD", ShiftCaseCycle.formAt("McDonald", ShiftCaseCycle.STEP_LAST_CHAR_UPPER))
        assertEquals("iPhonE", ShiftCaseCycle.formAt("iPhone", ShiftCaseCycle.STEP_LAST_CHAR_UPPER))
    }

    @Test
    fun `capitalized form normalizes the rest of the word`() {
        assertEquals("Hello", ShiftCaseCycle.formAt("hello", ShiftCaseCycle.STEP_CAPITALIZED))
        assertEquals("Iphone", ShiftCaseCycle.formAt("iPhone", ShiftCaseCycle.STEP_CAPITALIZED))
        assertEquals("Mcdonald", ShiftCaseCycle.formAt("McDonald", ShiftCaseCycle.STEP_CAPITALIZED))
    }

    @Test
    fun `already capitalized original has a coinciding capitalize step`() {
        // The rotation is fixed: step 2 of "Nacre" is "Nacre" itself.
        // The tracker still advances — the press is never re-interpreted.
        assertEquals("NacrE", ShiftCaseCycle.formAt("Nacre", ShiftCaseCycle.STEP_LAST_CHAR_UPPER))
        assertEquals("Nacre", ShiftCaseCycle.formAt("Nacre", ShiftCaseCycle.STEP_CAPITALIZED))
        assertEquals("NACRE", ShiftCaseCycle.formAt("Nacre", ShiftCaseCycle.STEP_ALL_CAPS))
        assertEquals("Nacre", ShiftCaseCycle.formAt("Nacre", ShiftCaseCycle.STEP_ORIGINAL))
    }

    @Test
    fun `single letter forms`() {
        assertEquals("a", ShiftCaseCycle.formAt("a", ShiftCaseCycle.STEP_ORIGINAL))
        assertEquals("A", ShiftCaseCycle.formAt("a", ShiftCaseCycle.STEP_LAST_CHAR_UPPER))
        assertEquals("A", ShiftCaseCycle.formAt("a", ShiftCaseCycle.STEP_CAPITALIZED))
        assertEquals("A", ShiftCaseCycle.formAt("a", ShiftCaseCycle.STEP_ALL_CAPS))
    }

    @Test
    fun `uncased characters pass through every form untouched`() {
        val original = "abcあ"
        assertEquals("abCあ", ShiftCaseCycle.formAt(original, ShiftCaseCycle.STEP_LAST_CHAR_UPPER))
        assertEquals("Abcあ", ShiftCaseCycle.formAt(original, ShiftCaseCycle.STEP_CAPITALIZED))
        assertEquals("ABCあ", ShiftCaseCycle.formAt(original, ShiftCaseCycle.STEP_ALL_CAPS))
        assertEquals("abcあ", ShiftCaseCycle.formAt(original, ShiftCaseCycle.STEP_ORIGINAL))
        // No cased letters at all: every form is the word itself.
        assertEquals("あ", ShiftCaseCycle.formAt("あ", ShiftCaseCycle.STEP_LAST_CHAR_UPPER))
        assertEquals("あ", ShiftCaseCycle.formAt("あ", ShiftCaseCycle.STEP_ALL_CAPS))
    }
}
