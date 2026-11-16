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
 * flanked by the left column (📋 / ◀ / 絵記 / あa1) and the
 * right strip (⌫ / ▶ / 空白 / 改行). The kana grid follows the iPhone
 * arrangement; the flanking columns follow Gboard's Japanese 12-key
 * (◀ second in the left column, ▶ second in the right strip, both
 * flanking the た row; mode switching consolidated into the single
 * あa1 key at the bottom of the left column — owner's own Gboard,
 * screenshot-verified), except the top slot: Gboard's ↩ seat holds
 * 📋, moved up from the abolished full-width bottom row.
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
    fun `left column seats clipboard on top and cursor-left second`() {
        // Gboard's left column: ↩ / ◀ / ☺記 / あa1. Nacre keeps the
        // lower three Gboard slots — ◀ second from the top, flanking
        // the た row; 絵記 third; the consolidated あa1 cycle key
        // (ModeCycle) closing the column at the bottom — but the top
        // slot departs: Nacre has no undo implementation, and the
        // inert placeholder that reserved Gboard's ↩ position gave
        // way to 📋 (owner, 2026-11-16), moved up from the full-width
        // bottom row it used to span (that row is gone). The former
        // ☆123 / ABC / あいう direct keys are also gone (owner
        // decision, 2026-11-16). FlickSideColumn renders
        // sideColumnKeys positionally.
        assertEquals(
            listOf(
                FlickEngine.SideColumnKey.Clipboard,
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
    fun `number keys are in grid order and taps stay the literal keys`() {
        // Grid reading order: 1 2 3 / 4 5 6 / 7 8 9 / + 0 . — and a tap
        // still inserts exactly the key's own character (symbols live on
        // the flicks only; tap behaviour is unchanged).
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "plus", "0", "dot"),
            FlickEngine.numberKeys.map { it.id },
        )
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "+", "0", "."),
            FlickEngine.numberKeys.map {
                FlickEngine.resolveFlick(it, FlickEngine.Direction.Tap)
            },
        )
        val rows = FlickEngine.numberKeys.chunked(3)
        assertEquals(listOf("1", "2", "3"), rows[0].map { it.tap })
        assertEquals(listOf("4", "5", "6"), rows[1].map { it.tap })
        assertEquals(listOf("7", "8", "9"), rows[2].map { it.tap })
        assertEquals(listOf("+", "0", "."), rows[3].map { it.tap })
    }

    @Test
    fun `number key up flicks follow the JIS shift layer`() {
        // The classic digit pairings (JIS hardware keyboard shift layer):
        // Shift+1..9 = ! " # $ % & ' ( ). Up is the direction rendered as
        // the key's sub-label, so this is the discoverable symbol per key.
        // Shift+0 is unassigned on JIS; 0 takes the phone-keypad * .
        val expectedUp = mapOf(
            "1" to "!", "2" to "\"", "3" to "#", "4" to "$", "5" to "%",
            "6" to "&", "7" to "'", "8" to "(", "9" to ")", "0" to "*",
        )
        for ((digit, symbol) in expectedUp) {
            val key = FlickEngine.numberKeys.first { it.id == digit }
            assertEquals(
                symbol,
                FlickEngine.resolveFlick(key, FlickEngine.Direction.Up),
            )
        }
    }

    @Test
    fun `plus key carries the full arithmetic set`() {
        // + − × ÷ = on one key: tap +, up −, down =, left ×, right ÷.
        val plus = FlickEngine.numberKeys.first { it.id == "plus" }
        assertEquals("+", FlickEngine.resolveFlick(plus, FlickEngine.Direction.Tap))
        assertEquals("-", FlickEngine.resolveFlick(plus, FlickEngine.Direction.Up))
        assertEquals("=", FlickEngine.resolveFlick(plus, FlickEngine.Direction.Down))
        assertEquals("×", FlickEngine.resolveFlick(plus, FlickEngine.Direction.Left))
        assertEquals("÷", FlickEngine.resolveFlick(plus, FlickEngine.Direction.Right))
    }

    @Test
    fun `number key companion flicks sit beside their primaries`() {
        // ¥ (left on 4) pairs with $; ) (right on 8) closes the ( pair;
        // the . key keeps , / : and gains / on the right for dates.
        val four = FlickEngine.numberKeys.first { it.id == "4" }
        assertEquals("¥", FlickEngine.resolveFlick(four, FlickEngine.Direction.Left))
        val eight = FlickEngine.numberKeys.first { it.id == "8" }
        assertEquals(")", FlickEngine.resolveFlick(eight, FlickEngine.Direction.Right))
        val dot = FlickEngine.numberKeys.first { it.id == "dot" }
        assertEquals(",", FlickEngine.resolveFlick(dot, FlickEngine.Direction.Up))
        assertEquals(":", FlickEngine.resolveFlick(dot, FlickEngine.Direction.Down))
        assertEquals("/", FlickEngine.resolveFlick(dot, FlickEngine.Direction.Right))
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
