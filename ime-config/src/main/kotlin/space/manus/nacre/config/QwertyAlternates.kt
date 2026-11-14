package space.manus.nacre.config

/**
 * Long-press alternates for the QWERTY layouts (Gboard/iPhone style).
 *
 * The number row is folded onto the top letter row: a long press on
 * q w e r t y u i o p yields 1 2 3 4 5 6 7 8 9 0, by key position.
 *
 * The same digits were already reachable by swiping up — the layout
 * data carries them as [KeyDef.swipeUp], which the key view also
 * renders as the small corner hint. Long-press is the discoverable
 * path to the same characters; the mapping lives here, as pure data,
 * so the layout definitions stay declarative and the pairing is
 * unit-testable.
 */
object QwertyAlternates {

    /** Top letter row → digit, in key order. */
    val topRowDigits: Map<String, String> = linkedMapOf(
        "q" to "1", "w" to "2", "e" to "3", "r" to "4", "t" to "5",
        "y" to "6", "u" to "7", "i" to "8", "o" to "9", "p" to "0",
    )

    fun digitFor(primary: String): String? = topRowDigits[primary]

    /**
     * Returns a copy of [layout] whose top (first) row keys carry their
     * digit as [KeyDef.longPress]. Keys that already define a long-press
     * keep it, and rows other than the first are returned untouched.
     * Only meaningful for layouts whose top row is the QWERTY letter row.
     */
    fun applyTopRowDigits(layout: KeyboardLayout): KeyboardLayout {
        if (layout.rows.isEmpty()) return layout
        val topRow = layout.rows.first().map { key ->
            if (key.longPress == null) {
                digitFor(key.primary)?.let { key.copy(longPress = it) } ?: key
            } else {
                key
            }
        }
        return layout.copy(rows = listOf(topRow) + layout.rows.drop(1))
    }
}
