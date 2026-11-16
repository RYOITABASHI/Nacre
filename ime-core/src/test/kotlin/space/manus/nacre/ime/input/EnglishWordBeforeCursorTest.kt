package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * englishWordBeforeCursor() — recovering the "current word" for English
 * prediction on the 12-key ABC pad.
 *
 * ABC letters commit immediately (iPhone-style direct insertion), so the
 * pad has no composing buffer for [InputEngine] to predict from: the word
 * must be re-derived from the editor text after every commit/delete.
 * Until this extraction existed, NOTHING on the pad ever called the
 * English predictor — the candidate bar stayed empty for alphabet input
 * even though predictEnglish itself (and the QWERTY English layer, which
 * predicts from its composing buffer) worked fine.
 */
class EnglishWordBeforeCursorTest {

    @Test
    fun `whole text is the word when it is all letters`() {
        assertEquals("hello", englishWordBeforeCursor("hello"))
    }

    @Test
    fun `word after a space`() {
        assertEquals("hello", englishWordBeforeCursor("say hello"))
    }

    @Test
    fun `empty when the cursor is not right after a letter`() {
        assertEquals("", englishWordBeforeCursor(""))
        assertEquals("", englishWordBeforeCursor("hello "))
        assertEquals("", englishWordBeforeCursor("hello!"))
        assertEquals("", englishWordBeforeCursor("hello。"))
    }

    @Test
    fun `digits break the word`() {
        assertEquals("", englishWordBeforeCursor("abc123"))
        assertEquals("abc", englishWordBeforeCursor("123abc"))
    }

    @Test
    fun `case is preserved for the case-aware predictor`() {
        assertEquals("Th", englishWordBeforeCursor("Th"))
        assertEquals("HELLO", englishWordBeforeCursor("say HELLO"))
        assertEquals("iPhone", englishWordBeforeCursor("iPhone"))
    }

    @Test
    fun `kana and emoji break the word`() {
        assertEquals("abc", englishWordBeforeCursor("あいうabc"))
        assertEquals("", englishWordBeforeCursor("abcあ"))
        assertEquals("abc", englishWordBeforeCursor("😀abc"))
        assertEquals("", englishWordBeforeCursor("abc😀"))
    }

    @Test
    fun `apostrophe breaks the run - the predictor sees the trailing fragment`() {
        // Same contract as the QWERTY path, where "'" commits the composing
        // buffer: the fragment after it starts a new prediction context.
        assertEquals("t", englishWordBeforeCursor("don't"))
    }

    @Test
    fun `single letter is a word`() {
        assertEquals("a", englishWordBeforeCursor("a"))
        assertEquals("I", englishWordBeforeCursor(" I"))
    }

    /**
     * The regression sequence, at the pure level the JVM harness supports:
     * entering Alpha (ModeCycle) and tapping letters commits them one by
     * one; after each commit the pad re-derives the word with this
     * function and feeds it to predictEnglish. Backspace shortens it,
     * a space ends it.
     */
    @Test
    fun `typed sequence tracks the word through commits and backspace`() {
        var editor = ""
        fun word() = englishWordBeforeCursor(editor)

        editor += "t"; assertEquals("t", word())
        editor += "h"; assertEquals("th", word())
        editor += "e"; assertEquals("the", word())
        // Backspace: the shortened word's completions must be republished.
        editor = editor.dropLast(1); assertEquals("th", word())
        editor += "y"; assertEquals("thy", word())
        // Space ends the word: no prediction context remains.
        editor += " "; assertEquals("", word())
        // A new word starts cleanly after the boundary.
        editor += "w"; assertEquals("w", word())
    }
}
