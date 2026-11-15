package space.manus.nacre.ime.input

/**
 * The case forms of the post-hoc Shift cycle for the 12-key ABC grid
 * (⇧ pressed AFTER typing). Pure string logic — no Android, no state.
 *
 * Every form is computed from the span's recorded ORIGINAL text (see
 * [PostHocShiftTracker]), never re-derived from the text currently on
 * screen. PR #69 derived the "next" form from the word's current case
 * pattern instead, which misclassified natural mixed-case words
 * (iPhone, McDonald): the derived step landed somewhere the user never
 * intended, and later steps rewrote the whole word's pattern. Deriving
 * from the original makes the cycle a fixed rotation the user can
 * predict — and step 0 restores EXACTLY what was typed, mixed case
 * included:
 *
 *   original → last-char-upper → Capitalize → ALL-CAPS → original …
 *
 * Steps act on cased letters only; uncased characters inside the span
 * (should any ever get in) pass through untouched at every step.
 */
object ShiftCaseCycle {

    /** The span exactly as originally typed. */
    const val STEP_ORIGINAL = 0

    /** Only the last cased letter is uppercase; all else as the original. */
    const val STEP_LAST_CHAR_UPPER = 1

    /** First cased letter uppercase, every other cased letter lowercase. */
    const val STEP_CAPITALIZED = 2

    /** Every cased letter uppercase. */
    const val STEP_ALL_CAPS = 3

    /** Number of steps in the cycle (including [STEP_ORIGINAL]). */
    const val STEP_COUNT = 4

    /** The form of [original] at [step]; [step] wraps modulo [STEP_COUNT]. */
    fun formAt(original: String, step: Int): String =
        when (step % STEP_COUNT) {
            STEP_LAST_CHAR_UPPER -> lastCasedUpper(original)
            STEP_CAPITALIZED -> capitalize(original)
            STEP_ALL_CAPS -> original.uppercase()
            else -> original
        }

    /** [word] with its last cased letter uppercased; every other character exactly as-is. */
    private fun lastCasedUpper(word: String): String {
        val idx = word.indexOfLast { it.isUpperCase() || it.isLowerCase() }
        if (idx < 0) return word
        return word.substring(0, idx) + word[idx].uppercase() + word.substring(idx + 1)
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
