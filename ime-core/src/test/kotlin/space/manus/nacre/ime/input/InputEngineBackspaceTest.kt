package space.manus.nacre.ime.input

import org.junit.Assert.*
import org.junit.Test

/**
 * backspaceDeleteCount() — the "backspace leaves one character behind" fix.
 *
 * InputConnection.deleteSurroundingText counts UTF-16 code units, not user-
 * visible characters. Emoji (and other supplementary-plane characters) are
 * encoded as a surrogate pair — two code units — so naively deleting 1 leaves
 * the other half of the pair behind as a lone, unpaired surrogate.
 */
class InputEngineBackspaceTest {

    @Test
    fun `deletes only 1 code unit for a plain ASCII character`() {
        assertEquals(1, backspaceDeleteCount("ab"))
    }

    @Test
    fun `deletes only 1 code unit for a hiragana character`() {
        assertEquals(1, backspaceDeleteCount("Aあ"))
    }

    @Test
    fun `deletes 2 code units for an emoji surrogate pair`() {
        // 😀 U+1F600 GRINNING FACE — encoded as the surrogate pair 😀
        val text = "A😀"
        assertEquals(2, backspaceDeleteCount(text))
    }

    @Test
    fun `deletes only 1 code unit when the two preceding units are NOT a valid surrogate pair`() {
        // Two ordinary BMP characters that happen to sit next to each other —
        // must not be misidentified as a surrogate pair.
        assertEquals(1, backspaceDeleteCount("xy"))
    }

    @Test
    fun `deletes only 1 code unit for a lone high surrogate with no following low surrogate`() {
        // Defensive: a malformed/lone high surrogate must never cause an
        // over-delete into unrelated preceding text.
        val text = "A\uD83D"
        assertEquals(1, backspaceDeleteCount(text))
    }

    @Test
    fun `deletes only 1 code unit when fewer than 2 characters are available`() {
        assertEquals(1, backspaceDeleteCount("A"))
        assertEquals(1, backspaceDeleteCount(""))
    }

    // ── conversionCancelKana — which buffer a conversion cancel reverts to ──
    //
    // Cancelling a conversion (backspace during conversion) restores the
    // composing kana. A flick-started conversion composes from the flick
    // buffer while the romaji buffer stays empty; deriving the revert text
    // from the romaji buffer alone erased the whole flick input.

    @Test
    fun `conversion cancel reverts to the flick kana for a flick conversion`() {
        assertEquals("あした", conversionCancelKana(flickComposingKana = "あした", romajiComposingKana = ""))
    }

    @Test
    fun `conversion cancel reverts to the romaji kana for a romaji conversion`() {
        assertEquals("あした", conversionCancelKana(flickComposingKana = "", romajiComposingKana = "あした"))
    }

    @Test
    fun `conversion cancel prefers the flick buffer when both are present`() {
        assertEquals("あした", conversionCancelKana(flickComposingKana = "あした", romajiComposingKana = "ちがう"))
    }

    @Test
    fun `conversion cancel with no composition reverts to empty`() {
        assertEquals("", conversionCancelKana(flickComposingKana = "", romajiComposingKana = ""))
    }

    @Test
    fun `handles a multi-codepoint emoji sequence, deleting only the trailing pair`() {
        // 👍🏽 (thumbs up + medium skin tone modifier) is two surrogate pairs
        // back-to-back. A single backspace should remove just the trailing
        // pair (the modifier), matching standard Android/iOS behavior for
        // combining emoji modifiers — not the whole 4-code-unit sequence.
        val thumbsUp = "👍" // 👍 U+1F44D
        val skinToneModifier = "🏽" // 🏽 U+1F3FD
        val text = thumbsUp + skinToneModifier
        assertEquals(2, backspaceDeleteCount(text))
    }
}
