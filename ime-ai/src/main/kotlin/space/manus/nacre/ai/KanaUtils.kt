package space.manus.nacre.ai

/**
 * Pure kana helpers shared by the zenz conversion path.
 *
 * Extracted as top-level pure functions (no Android dependencies) so they are
 * unit-testable on a plain JVM, per this codebase's convention. Private copies
 * of hiragana→katakana exist in LlmService / NacreDictionary / TextTransformer;
 * new code should use this one.
 */
object KanaUtils {

    /**
     * Convert hiragana (U+3041..U+3096) to katakana by the fixed +0x60 offset.
     * All other characters pass through unchanged.
     */
    fun hiraganaToKatakana(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ch in 'ぁ'..'ゖ') sb.append((ch.code + 0x60).toChar())
            else sb.append(ch)
        }
        return sb.toString()
    }
}

/**
 * Validation of raw zenz generation output before it may become a conversion
 * candidate. zenz is a small char-level model: it can emit the control
 * characters it was prompted with (U+EE00..U+EE07) or run past its stop token
 * into hallucinated follow-up conversions. Anything suspicious is dropped —
 * the engine candidates remain, exactly as without zenz.
 */
object ZenzOutput {

    /**
     * @param raw raw generation from the zenz model
     * @param katakanaReading the katakana reading that was sent to the model
     * @param hiraganaReading the original hiragana composing string
     * @return the candidate surface, or null when the output must be skipped:
     *   blank, equal to either reading (no conversion happened), or starting
     *   with / containing control-char leftovers. Output is truncated at the
     *   first control character (U+EE00..U+EE07) if one appears mid-text.
     */
    fun sanitize(raw: String?, katakanaReading: String, hiraganaReading: String): String? {
        if (raw.isNullOrBlank()) return null
        val controlIdx = raw.indexOfFirst { it.code in 0xEE00..0xEE07 }
        val truncated = if (controlIdx >= 0) raw.substring(0, controlIdx) else raw
        val cleaned = truncated.trim()
        if (cleaned.isEmpty()) return null
        if (cleaned == katakanaReading || cleaned == hiraganaReading) return null
        return cleaned
    }
}
