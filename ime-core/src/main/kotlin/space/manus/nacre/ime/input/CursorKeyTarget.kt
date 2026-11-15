package space.manus.nacre.ime.input

/**
 * Target caret position for a 12-key pad ◀ / ▶ cursor-key tap.
 *
 * Pure function over the editor's extracted text and selection — no
 * Android object method calls — so it is directly unit testable on the
 * JVM, matching this package's convention (see [computeEnterOutcome]).
 *
 * The tap is applied with `InputConnection.setSelection`, never with a
 * raw DPAD key event: a DPAD event the editor cannot consume (caret
 * already at the end of the text) propagates to the activity's focus
 * search, and when focus lands on a non-text widget Android hides the
 * IME — the "▶ sometimes closes the keyboard" bug. With setSelection
 * the caret simply stays where it is at the text edges, inside the
 * field, and the keyboard stays up.
 *
 * Rules (Android editor conventions):
 * - An active selection collapses to the edge the key points at
 *   (▶ → selection end, ◀ → selection start) without moving further.
 *   The two endpoints may arrive in either order (anchor vs. caret),
 *   so they are treated as an unordered range.
 * - Otherwise the caret moves by one Unicode code point, so it never
 *   lands inside a surrogate pair (emoji).
 * - At the text edges the caret does not move.
 *
 * The result is relative to [text] (the extracted window); the caller
 * adds ExtractedText.startOffset before calling setSelection, which
 * takes full-document offsets.
 *
 * Returns -1 when the selection is unknown (caller must not call
 * setSelection at all in that case).
 */
internal fun computeCursorKeyTarget(
    text: String,
    selectionStart: Int,
    selectionEnd: Int,
    dx: Int,
): Int {
    if (selectionStart < 0 || selectionEnd < 0) return -1
    if (dx == 0) return selectionStart.coerceIn(0, text.length)
    val lo = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    val hi = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    if (hi > lo) {
        return if (dx > 0) hi else lo
    }
    val caret = lo
    return if (dx > 0) {
        if (caret >= text.length) text.length
        else caret + Character.charCount(text.codePointAt(caret))
    } else {
        if (caret <= 0) 0
        else caret - Character.charCount(text.codePointBefore(caret))
    }
}
