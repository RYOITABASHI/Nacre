package space.manus.nacre.ime.input

/**
 * Post-hoc Shift capitalization for the 12-key ABC grid: pressing ⇧ AFTER
 * typing a word rewrites the word before the cursor instead of (only)
 * pre-arming the next letter. Pure string logic — no Android, no state —
 * so the whole decision is unit-testable; [InputEngine] supplies the text
 * before the cursor (via the same getTextBeforeCursor seam the ABC
 * replace-cycle already verifies against) and applies a non-null result
 * with deleteSurroundingText + commitText.
 *
 * The cycle is derived from the word's CURRENT case pattern, so repeated
 * ⇧ presses keep advancing it without any press counter to desync:
 *
 *   hello → hellO → Hello → HELLO → hello → hellO …
 *
 * 1. all-lowercase → uppercase the LAST cased letter only. The just-typed
 *    letter is the common case (type "a", notice, ⇧ → "A"), and after an
 *    ABC multi-tap the sealed letter is exactly this character.
 * 2. only-last-uppercase → Capitalize the word (first cased letter upper,
 *    the rest lower).
 * 3. Capitalized → ALL-CAPS.
 * 4. ALL-CAPS → all-lowercase (cycle restart).
 * Any other mix (e.g. "hELLO") normalizes to Capitalized, so no press is
 * ever a dead no-op. Single letters collapse to a plain toggle (a ⇄ A):
 * "A" is ALL-CAPS, which rule 4 maps back to "a".
 *
 * Uncased letters inside the run (kana typed just before switching to the
 * ABC grid, e.g. "abcあ") are skipped over, never treated as the cycle's
 * target: the steps act on cased letters, so a mixed run still advances
 * (abcあ → abCあ → Abcあ → ABCあ → abcあ) instead of uppercasing an
 * unchangeable final kana forever.
 */
object ShiftCaseCycle {

    /**
     * The letter word immediately before the cursor in [textBeforeCursor]:
     * the trailing run of letters, or "" when the character right before
     * the cursor is not a letter (start of field, after a space, digit or
     * punctuation) — the caller then falls back to classic pre-arm Shift.
     */
    fun wordBeforeCursor(textBeforeCursor: String): String {
        var start = textBeforeCursor.length
        while (start > 0 && textBeforeCursor[start - 1].isLetter()) start--
        return textBeforeCursor.substring(start)
    }

    /**
     * The next form of [word] in the cycle described above, or null when
     * the word has no cased letter at all (e.g. a kana-only run — Shift
     * has nothing to change there, so the press falls back to pre-arm).
     */
    fun nextTransform(word: String): String? {
        if (word.isEmpty()) return null
        val cased = word.filter { it.isUpperCase() || it.isLowerCase() }
        if (cased.isEmpty()) return null

        // ALL-CAPS → all-lowercase (cycle restart). Checked first so a
        // single uppercase letter toggles back down instead of matching
        // the Capitalized rule below.
        if (cased.all { it.isUpperCase() }) return word.lowercase()

        // Capitalized → ALL-CAPS.
        if (cased.first().isUpperCase() && cased.drop(1).all { it.isLowerCase() }) {
            return word.uppercase()
        }

        // Only the last cased letter is uppercase → Capitalize.
        if (cased.last().isUpperCase() && cased.dropLast(1).all { it.isLowerCase() }) {
            return capitalize(word)
        }

        // All-lowercase → uppercase the last cased letter only (an
        // uncased trailing kana is skipped, not "uppercased" as a no-op).
        if (cased.all { it.isLowerCase() }) {
            val idx = word.indexOfLast { it.isLowerCase() }
            return word.substring(0, idx) + word[idx].uppercase() + word.substring(idx + 1)
        }

        // Any other mix normalizes to Capitalized.
        return capitalize(word)
    }

    /**
     * One-call decision for the engine: given the text before the cursor,
     * returns the replacement for the word before the cursor, or null when
     * this ⇧ press should fall back to classic pre-arm (no letter word
     * before the cursor, or the word has no case to change).
     */
    fun decide(textBeforeCursor: String): String? {
        val word = wordBeforeCursor(textBeforeCursor)
        if (word.isEmpty()) return null
        return nextTransform(word)
    }

    /** First cased letter uppercase, every other cased letter lowercase; uncased letters pass through. */
    private fun capitalize(word: String): String {
        var firstCasedSeen = false
        return buildString(word.length) {
            for (ch in word) {
                when {
                    !ch.isUpperCase() && !ch.isLowerCase() -> append(ch)
                    !firstCasedSeen -> {
                        append(ch.uppercase())
                        firstCasedSeen = true
                    }
                    else -> append(ch.lowercase())
                }
            }
        }
    }
}
