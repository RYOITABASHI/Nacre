package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [WritingTools]: target extraction (selection priority,
 * paragraph rule, suffix guarantee), per-operation instructions, the
 * per-app-tone polite style, result cleanup, and the apply-time
 * paragraph guard.
 */
class WritingToolsTest {

    // ── resolveTarget ────────────────────────────────────────────────

    @Test
    fun `selection wins over paragraph`() {
        val r = WritingTools.resolveTarget("選択された文", "前文\n\n段落の文")
        assertTrue(r is WritingTools.TargetResolution.Found)
        val target = (r as WritingTools.TargetResolution.Found).target
        assertTrue(target is WritingTools.Target.Selection)
        assertEquals("選択された文", target.text)
    }

    @Test
    fun `empty selection falls back to paragraph`() {
        val r = WritingTools.resolveTarget("", "そのまま段落です")
        assertTrue(r is WritingTools.TargetResolution.Found)
        val target = (r as WritingTools.TargetResolution.Found).target
        assertTrue(target is WritingTools.Target.Paragraph)
        assertEquals("そのまま段落です", target.text)
    }

    @Test
    fun `over-long selection is refused, not truncated`() {
        val long = "あ".repeat(WritingTools.MAX_TARGET_CHARS + 1)
        val r = WritingTools.resolveTarget(long, "段落")
        assertEquals(WritingTools.TargetResolution.SelectionTooLong, r)
    }

    @Test
    fun `no selection and no text is None`() {
        assertEquals(
            WritingTools.TargetResolution.None,
            WritingTools.resolveTarget(null, null),
        )
        assertEquals(
            WritingTools.TargetResolution.None,
            WritingTools.resolveTarget(null, ""),
        )
        assertEquals(
            WritingTools.TargetResolution.None,
            WritingTools.resolveTarget(null, "  \n\n  "),
        )
    }

    // ── extractParagraph ─────────────────────────────────────────────

    @Test
    fun `paragraph is the block after the last blank line`() {
        assertEquals(
            "二つ目の段落です。",
            WritingTools.extractParagraph("一つ目の段落です。\n\n二つ目の段落です。"),
        )
        assertEquals(
            "後の段落",
            WritingTools.extractParagraph("前\n\n中\n\n後の段落"),
        )
    }

    @Test
    fun `blank line may contain spaces`() {
        assertEquals(
            "新しい段落",
            WritingTools.extractParagraph("古い段落\n  \n新しい段落"),
        )
    }

    @Test
    fun `single newlines do not split the paragraph`() {
        val text = "一行目\n二行目\n三行目"
        assertEquals(text, WritingTools.extractParagraph(text))
    }

    @Test
    fun `crlf blank line is a boundary and result stays a raw suffix`() {
        val raw = "古い段落\r\n\r\n新しい段落"
        val paragraph = WritingTools.extractParagraph(raw)
        assertEquals("新しい段落", paragraph)
        assertTrue(raw.endsWith(paragraph!!))
    }

    @Test
    fun `cursor right after a blank line has no paragraph`() {
        assertNull(WritingTools.extractParagraph("段落です。\n\n"))
        assertNull(WritingTools.extractParagraph("段落です。\n\n  "))
    }

    @Test
    fun `leading whitespace is stripped and result is still a suffix`() {
        val raw = "前の段落\n\n   次の段落"
        val paragraph = WritingTools.extractParagraph(raw)
        assertEquals("次の段落", paragraph)
        assertTrue(raw.endsWith(paragraph!!))
    }

    @Test
    fun `over-long paragraph is cut to a suffix of max chars`() {
        val raw = "あ".repeat(WritingTools.MAX_TARGET_CHARS + 500)
        val paragraph = WritingTools.extractParagraph(raw)
        assertEquals(WritingTools.MAX_TARGET_CHARS, paragraph!!.length)
        assertTrue(raw.endsWith(paragraph))
    }

    @Test
    fun `paragraph cut never starts mid surrogate pair`() {
        // Total length 2001 UTF-16 units: the MAX cut lands on the low
        // surrogate of a pair, which must be dropped (result stays a
        // suffix of the raw text).
        val raw = "a" + "𐮷".repeat(WritingTools.MAX_TARGET_CHARS)
        val paragraph = WritingTools.extractParagraph(raw)
        assertFalse(paragraph!!.first().isLowSurrogate())
        assertTrue(raw.endsWith(paragraph))
    }

    // ── isCollapsedSelection ─────────────────────────────────────────

    @Test
    fun `collapsed selection only for a plain cursor`() {
        assertTrue(WritingTools.isCollapsedSelection(0, 0))
        assertTrue(WritingTools.isCollapsedSelection(5, 5))
        assertFalse(WritingTools.isCollapsedSelection(3, 7))
        assertFalse(WritingTools.isCollapsedSelection(7, 3))
        assertFalse(WritingTools.isCollapsedSelection(-1, -1))
    }

    // ── paragraphIntact ──────────────────────────────────────────────

    @Test
    fun `paragraph intact only when the suffix still matches exactly`() {
        assertTrue(WritingTools.paragraphIntact("前文そして対象の段落", "対象の段落"))
        assertTrue(WritingTools.paragraphIntact("対象の段落", "対象の段落"))
        assertFalse(WritingTools.paragraphIntact("前文そして変わった段落", "対象の段落"))
        assertFalse(WritingTools.paragraphIntact("短い", "対象の段落"))
        assertFalse(WritingTools.paragraphIntact(null, "対象の段落"))
        assertFalse(WritingTools.paragraphIntact("", "対象の段落"))
    }

    // ── instructions ─────────────────────────────────────────────────

    @Test
    fun `every instruction demands bare text output only`() {
        for (op in WritingTools.Op.values()) {
            val instruction = WritingTools.instructionFor(op)
            assertTrue("$op must forbid preamble", instruction.contains("前置き"))
            assertTrue("$op must demand text-only output", instruction.contains("のみ"))
        }
    }

    @Test
    fun `polite style switches the polite instruction`() {
        val desuMasu = WritingTools.instructionFor(
            WritingTools.Op.POLITE, WritingTools.PoliteStyle.DESU_MASU,
        )
        val keigo = WritingTools.instructionFor(
            WritingTools.Op.POLITE, WritingTools.PoliteStyle.KEIGO,
        )
        assertTrue(desuMasu.contains("です・ます"))
        assertTrue(keigo.contains("ビジネス敬語"))
        assertFalse(desuMasu == keigo)
        // The style must not leak into other operations.
        assertEquals(
            WritingTools.instructionFor(WritingTools.Op.PROOFREAD),
            WritingTools.instructionFor(
                WritingTools.Op.PROOFREAD, WritingTools.PoliteStyle.KEIGO,
            ),
        )
    }

    @Test
    fun `bullets instruction uses the house bullet marker`() {
        assertTrue(WritingTools.instructionFor(WritingTools.Op.BULLETS).contains("・で始まる行"))
    }

    @Test
    fun `summarize instruction forbids bullet form`() {
        assertTrue(WritingTools.instructionFor(WritingTools.Op.SUMMARIZE).contains("箇条書きにはせず"))
    }

    // ── politeStyleFor ───────────────────────────────────────────────

    @Test
    fun `keigo preset resolves to keigo style, everything else to desu-masu`() {
        assertEquals(
            WritingTools.PoliteStyle.KEIGO,
            WritingTools.politeStyleFor("keigo"),
        )
        assertEquals(
            WritingTools.PoliteStyle.DESU_MASU,
            WritingTools.politeStyleFor("clean"),
        )
        assertEquals(
            WritingTools.PoliteStyle.DESU_MASU,
            WritingTools.politeStyleFor(null),
        )
    }

    // ── cleanResult ──────────────────────────────────────────────────

    @Test
    fun `plain result passes through trimmed`() {
        assertEquals("直した文です。", WritingTools.cleanResult("  直した文です。 \n"))
    }

    @Test
    fun `code fence wrapping is stripped`() {
        assertEquals(
            "直した文です。",
            WritingTools.cleanResult("```\n直した文です。\n```"),
        )
        assertEquals(
            "直した文です。",
            WritingTools.cleanResult("```text\n直した文です。\n```"),
        )
    }

    @Test
    fun `quotes wrapping the whole output are stripped`() {
        assertEquals("直した文です。", WritingTools.cleanResult("「直した文です。」"))
        assertEquals("直した文です。", WritingTools.cleanResult("“直した文です。”"))
    }

    @Test
    fun `inner quotes are not stripped`() {
        val text = "彼は「行く」と言った。"
        assertEquals(text, WritingTools.cleanResult(text))
    }

    @Test
    fun `label preamble line is stripped`() {
        assertEquals(
            "直した文です。",
            WritingTools.cleanResult("校正結果：\n直した文です。"),
        )
        assertEquals(
            "・要点1\n・要点2",
            WritingTools.cleanResult("箇条書き:\n・要点1\n・要点2"),
        )
    }

    @Test
    fun `a real first sentence ending a line is not a preamble`() {
        val text = "これは本文です。\n次の文です。"
        assertEquals(text, WritingTools.cleanResult(text))
    }

    @Test
    fun `blank results are unusable`() {
        assertNull(WritingTools.cleanResult(""))
        assertNull(WritingTools.cleanResult("   \n  "))
        assertNull(WritingTools.cleanResult("「」"))
    }
}
