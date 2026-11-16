package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import space.manus.nacre.config.DefaultLayouts
import space.manus.nacre.config.KeyAction
import space.manus.nacre.config.KeyboardLayout
import space.manus.nacre.config.SymbolCatalog

/**
* The QWERTY emoji/symbol UX contract (owner verdict on build-462:
* "qwertyの絵文字や記号のUX完全にミスった").
*
* Before this fix, the standard QWERTY bottom row had NO emoji key and
* NO symbols key — both panels were reachable only through the Fn layer
* or the compact layout — and the symbols panel had no Japanese
* punctuation category at all. These tests pin the one-tap access on
* every QWERTY surface and the punctuation set's contents, so a future
* layout edit cannot silently bury them again.
*/
class QwertySymbolUxTest {

private fun bottomActions(layout: KeyboardLayout): List<KeyAction> =
layout.rows.last().map { it.action}

@Test
fun `standard qwerty bottom row has one-tap emoji and symbols keys`() {
val actions = bottomActions(DefaultLayouts.qwertyBase)
assertEquals(1, actions.count { it is KeyAction.Emoji})
assertEquals(1, actions.count { it is KeyAction.Symbols})
// Both sit left of the space bar, where Gboard keeps them.
val spaceIndex = actions.indexOfFirst { it is KeyAction.Space}
assertTrue(spaceIndex > 0)
assertTrue(actions.indexOfFirst { it is KeyAction.Emoji} in 0 until spaceIndex)
assertTrue(actions.indexOfFirst { it is KeyAction.Symbols} in 0 until spaceIndex)
// The rest of the modifier row survives.
assertTrue(actions.any { it is KeyAction.Shift})
assertTrue(actions.any { it is KeyAction.Fn})
assertTrue(actions.any { it is KeyAction.Enter})
}

@Test
fun `compact and fn1 layouts keep their emoji and symbols keys`() {
for (layout in listOf(DefaultLayouts.compactJapaneseQwerty, DefaultLayouts.fnLayer1)) {
val actions = bottomActions(layout)
assertEquals(1, actions.count { it is KeyAction.Emoji})
assertEquals(1, actions.count { it is KeyAction.Symbols})
}
}

@Test
fun `standard qwerty bottom row total width is unchanged and space stays usable`() {
val row = DefaultLayouts.qwertyBase.rows.last()
val total = row.sumOf { it.widthMultiplier.toDouble()}
assertEquals(7.3, total, 0.001)
val space = row.first { it.action is KeyAction.Space}
assertTrue(space.widthMultiplier >= 1.5f)
}

@Test
fun `letter rows are untouched`() {
val rows = DefaultLayouts.qwertyBase.rows
assertEquals(
listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
rows[0].map { it.primary},
)
assertEquals("a", rows[1].first().primary)
assertTrue(rows[2].last().action is KeyAction.Backspace)
}

@Test
fun `japanese punctuation set covers the mid-sentence essentials`() {
val set = SymbolCatalog.japanesePunctuation
val essentials = listOf(
"、", "。", "・", "「", "」", "『", "』", "【", "】", "（", "）",
"！", "？", "〜", "…", "‥", "：", "；",
)
for (essential in essentials) {
assertTrue("missing $essential", essential in set)
}
assertEquals("no duplicates", set.size, set.distinct().size)
// Fills complete rows in the panel's 8-column grid.
assertEquals(0, set.size % 8)
// Single BMP code points only — no multi-char entries.
assertTrue(set.all { it.length == 1})
}
}
