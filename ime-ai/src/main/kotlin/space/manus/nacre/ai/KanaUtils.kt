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
 * zenz prompt construction (azooKey zenzai docs, Docs/zenzai.md).
 *
 * No context: `U+EE00 + katakana reading + U+EE01` — the format Nacre has
 * shipped since the zenz integration; kept byte-identical so the no-context
 * behavior cannot drift.
 *
 * With left context (zenz-v3 format, the model is zenz-v3.2-small):
 * `U+EE02 + context + U+EE00 + katakana reading + U+EE01`. The context is
 * the preceding text in its surface (converted) form — the docs' own
 * example passes `leftSideContext: "私の名前は"` — placed BEFORE the
 * input marker, not after the reading (that was the v2 layout).
 */
object ZenzPrompt {

    fun build(katakanaReading: String, leftContext: String): String =
        if (leftContext.isBlank()) {
            "\uEE00" + katakanaReading + "\uEE01"
        } else {
            "\uEE02" + leftContext + "\uEE00" + katakanaReading + "\uEE01"
        }
}

/**
 * Extraction of the zenz left context from the raw text an editor returns
 * for "text before cursor". Pure and JVM-testable; the caller (InputEngine)
 * supplies the fetch and the current composing display string.
 *
 * Editors differ on whether the returned text includes the composing
 * region: when it does, the text ends with the composing display, which is
 * stripped — the context is the COMMITTED text before the composing
 * region, never the reading being converted. The result is capped at
 * [MAX_CHARS], preferring a suffix that starts at a sentence boundary
 * (previous sentence + current sentence head) when that keeps at least
 * [MIN_ALIGNED_CHARS]; a raw tail is used otherwise, because the model is
 * a char-level LM and a mid-sentence fragment still carries the topic.
 * The model's control characters (U+EE00..U+EE07) are stripped so editor
 * text can never smuggle prompt structure into the context slot.
 */
object ZenzContext {

    const val MAX_CHARS = 60
    private const val MIN_ALIGNED_CHARS = 30
    private val SENTENCE_ENDS = charArrayOf('。', '！', '？', '.', '!', '?', '\n')

    fun extract(rawBeforeCursor: String?, composingDisplay: String): String {
        if (rawBeforeCursor.isNullOrEmpty()) return ""
        var text = rawBeforeCursor
        if (composingDisplay.isNotEmpty() && text.endsWith(composingDisplay)) {
            text = text.dropLast(composingDisplay.length)
        }
        text = text.filterNot { it.code in 0xEE00..0xEE07 }
        text = text.trimEnd()
        if (text.length <= MAX_CHARS) return text
        val tailStart = text.length - MAX_CHARS
        var s = tailStart
        while (s < text.length) {
            if (text[s - 1] in SENTENCE_ENDS) {
                if (text.length - s >= MIN_ALIGNED_CHARS) {
                    return text.substring(s).trimStart(' ', '　')
                }
                break
            }
            s++
        }
        return text.substring(tailStart)
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
