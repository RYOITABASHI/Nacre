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
 * flanked by the left mode column (☆123 / ABC / あいう / 絵記) and the
 * right strip (⌫ / 空白 / tall 改行). If the data order ever drifts, the
 * rendered grid silently stops being the iPhone grid — this test pins it.
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
}
