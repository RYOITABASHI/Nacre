package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import space.manus.nacre.config.DefaultLayouts
import space.manus.nacre.config.KeyDef
import space.manus.nacre.config.KeyboardLayout
import space.manus.nacre.config.QwertyAlternates

/**
 * The QWERTY long-press alternates (Gboard/iPhone style): the number row
 * folded onto the top letter row, q w e r t y u i o p → 1 2 3 4 5 6 7 8 9 0.
 * The pairing is pure data in [QwertyAlternates]; if it drifts, the top
 * row silently long-presses the wrong digits, so it is pinned here along
 * with the layouts that ship it (default + compact QWERTY).
 */
class QwertyAlternatesTest {

    private val topRowLetters = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    private val topRowDigits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")

    @Test
    fun `top row letters map to digits 1 through 0 in key order`() {
        assertEquals(topRowLetters, QwertyAlternates.topRowDigits.keys.toList())
        assertEquals(topRowDigits, QwertyAlternates.topRowDigits.values.toList())
        topRowLetters.zip(topRowDigits).forEach { (letter, digit) ->
            assertEquals(digit, QwertyAlternates.digitFor(letter))
        }
    }

    @Test
    fun `non top-row keys have no digit alternate`() {
        assertNull(QwertyAlternates.digitFor("a"))
        assertNull(QwertyAlternates.digitFor("z"))
        assertNull(QwertyAlternates.digitFor("1"))
        assertNull(QwertyAlternates.digitFor(" "))
    }

    @Test
    fun `default qwerty top row carries the digits as long-press`() {
        val topRow = DefaultLayouts.qwertyBase.rows.first()
        assertEquals(topRowLetters, topRow.map { it.primary })
        assertEquals(topRowDigits, topRow.map { it.longPress })
    }

    @Test
    fun `compact qwerty top row carries the digits as long-press`() {
        val topRow = DefaultLayouts.compactJapaneseQwerty.rows.first()
        assertEquals(topRowLetters, topRow.map { it.primary })
        assertEquals(topRowDigits, topRow.map { it.longPress })
    }

    @Test
    fun `rows below the top row get no long-press alternates`() {
        listOf(DefaultLayouts.qwertyBase, DefaultLayouts.compactJapaneseQwerty).forEach { layout ->
            layout.rows.drop(1).forEach { row ->
                row.forEach { key -> assertNull(key.longPress) }
            }
        }
    }

    @Test
    fun `apply keeps existing long-press and other rows untouched`() {
        val customTop = KeyDef("q", longPress = "!")
        val secondRow = listOf(KeyDef("a"))
        val layout = KeyboardLayout(rows = listOf(listOf(customTop), secondRow))

        val result = QwertyAlternates.applyTopRowDigits(layout)

        assertEquals("!", result.rows[0][0].longPress)
        assertSame(secondRow, result.rows[1])
    }

    @Test
    fun `apply on empty layout returns it unchanged`() {
        val layout = KeyboardLayout(rows = emptyList())
        assertSame(layout, QwertyAlternates.applyTopRowDigits(layout))
    }
}
