package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Positional data behind the 12-key pad's iPhone-stock arrangement.
 *
 * The composables in FlickInputPad render the kana grid positionally from
 * [FlickEngine.kanaKeys] (indices 0-9) plus [FlickEngine.punctKey], in the
 * iPhone stock reading order:
 *
 *     あ か さ
 *     た な は
 *     ま や ら
 *     ゛゜小 わ 、。？！   (゛゜小 is the dedicated DakutenKeyView)
 *
 * flanked by the left column (↩ slot / ◀ / 絵記 / あa1) and the
 * right strip (⌫ / ▶ / 空白 / 改行). The kana grid follows the iPhone
 * arrangement; the flanking columns follow Gboard's Japanese 12-key
 * (◀ second in the left column, ▶ second in the right strip, both
 * flanking the た row; mode switching consolidated into the single
 * あa1 key at the bottom of the left column — owner's own Gboard,
 * screenshot-verified).
 * If the data order ever drifts, the rendered pad silently stops being
 * the stock arrangement — this test pins it.
 * Behavioural parity (cycles, tap counts, dakuten) lives in FlickParityTest.
 */
class FlickLayoutTest {

    @Test
    fun `kana keys are in iPhone grid reading order`() {
        assertEquals(
            listOf("あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ"),
            FlickEngine.kanaKeys.map { it.id },
        )
    }

    @Test
    fun `kana grid rows are gojuuon rows`() {
        val rows = FlickEngine.kanaKeys.chunked(3)
        assertEquals(listOf("あ", "か", "さ"), rows[0].map { it.id })
        assertEquals(listOf("た", "な", "は"), rows[1].map { it.id })
        assertEquals(listOf("ま", "や", "ら"), rows[2].map { it.id })
        // Row 4 is ゛゜小 (dedicated key) | わ | 、。？！ — only わ is a kana key.
        assertEquals(listOf("わ"), rows[3].map { it.id })
    }

    @Test
    fun `punctuation key completes the bottom row`() {
        // Bottom-right cell of the kana grid: tap 、, with 。 one cycle step on.
        assertEquals("punct", FlickEngine.punctKey.id)
        assertEquals("、", FlickEngine.punctKey.tap)
        assertEquals("。", FlickEngine.punctKey.left)
    }

    @Test
    fun `left column seats cursor-left second like Gboard`() {
        // Gboard's left column: ↩ / ◀ / ☺記 / あa1. Nacre has no undo
        // implementation, so the top slot is the inert UndoSlot
        // placeholder reserving the ↩ position; ◀ stays second from
        // the top, flanking the た row; 絵記 is third; and the
        // consolidated あa1 cycle key (ModeCycle) closes the column at
        // the bottom. The former ☆123 / ABC / あいう direct keys are
        // gone (owner decision, 2026-11-16). FlickSideColumn renders
        // sideColumnKeys positionally.
        assertEquals(
            listOf(
                FlickEngine.SideColumnKey.UndoSlot,
                FlickEngine.SideColumnKey.CursorLeft,
                FlickEngine.SideColumnKey.Emoji,
                FlickEngine.SideColumnKey.ModeCycle,
            ),
            FlickEngine.sideColumnKeys,
        )
        assertEquals(
            FlickEngine.SideColumnKey.ModeCycle,
            FlickEngine.sideColumnKeys.last(),
        )
    }

    @Test
    fun `right strip seats cursor-right second like Gboard`() {
        // Gboard's ▶: right strip, second from the top, directly under
        // ⌫. FlickRightStrip renders rightStripKeys positionally.
        assertEquals(
            listOf(
                FlickEngine.RightStripKey.Backspace,
                FlickEngine.RightStripKey.CursorRight,
                FlickEngine.RightStripKey.Space,
                FlickEngine.RightStripKey.Enter,
            ),
            FlickEngine.rightStripKeys,
        )
    }
}
