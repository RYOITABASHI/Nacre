package space.manus.nacre.ai

import org.junit.Assert.*
import org.junit.Test

class ZenzContextTest {

    // ── ZenzPrompt.build ────────────────────────────────────────────────

    @Test
    fun `prompt without context is byte-identical to the legacy format`() {
        assertEquals("\uEE00ミズヲクダサイ\uEE01", ZenzPrompt.build("ミズヲクダサイ", ""))
        assertEquals("\uEE00ミズヲクダサイ\uEE01", ZenzPrompt.build("ミズヲクダサイ", "   "))
    }

    @Test
    fun `prompt with context uses the v3 layout - context before the input marker`() {
        // Docs example shape: leftSideContext "私の名前は" precedes U+EE00.
        assertEquals(
            "\uEE02私の名前は\uEE00ナマエハ\uEE01",
            ZenzPrompt.build("ナマエハ", "私の名前は"),
        )
    }

    // ── ZenzContext.extract ─────────────────────────────────────────────

    @Test
    fun `extract returns empty for null or empty input`() {
        assertEquals("", ZenzContext.extract(null, "なまえは"))
        assertEquals("", ZenzContext.extract("", "なまえは"))
    }

    @Test
    fun `extract strips the composing kana an editor includes in the before-cursor text`() {
        // Typing-time: composing display is the kana itself.
        assertEquals("私の名前は", ZenzContext.extract("私の名前はなまえは", "なまえは"))
    }

    @Test
    fun `extract strips the composing surface shown during conversion`() {
        // Conversion-time: composing display is the first candidate surface.
        assertEquals(
            "昨日の会議で決めた。",
            ZenzContext.extract("昨日の会議で決めた。水をください", "水をください"),
        )
    }

    @Test
    fun `extract keeps the text when the editor excludes the composing region`() {
        assertEquals("私の名前は", ZenzContext.extract("私の名前は", "なまえは"))
    }

    @Test
    fun `extract of a composing-only field is empty`() {
        assertEquals("", ZenzContext.extract("なまえは", "なまえは"))
    }

    @Test
    fun `extract passes short text through and trims trailing whitespace`() {
        assertEquals("前の文です。次の文の頭", ZenzContext.extract("前の文です。次の文の頭", ""))
        assertEquals("私の名前は", ZenzContext.extract("私の名前は ", ""))
    }

    @Test
    fun `extract strips prompt control characters from editor text`() {
        assertEquals("私の名前は", ZenzContext.extract("私の\uEE02名前は", ""))
        assertEquals("私の名前は", ZenzContext.extract("私の\uEE00名前は\uEE01", ""))
    }

    @Test
    fun `extract caps long text at MAX_CHARS`() {
        val text = "あ".repeat(100)
        val out = ZenzContext.extract(text, "")
        assertEquals(ZenzContext.MAX_CHARS, out.length)
        assertEquals("あ".repeat(ZenzContext.MAX_CHARS), out)
    }

    @Test
    fun `extract aligns the cap to a sentence boundary when that keeps enough context`() {
        // 82 chars: old sentence (50+。) + previous sentence (20+。) + head (10).
        // The cap window starts mid-old-sentence; the aligned suffix
        // (previous sentence + head, 31 chars) wins over the raw tail.
        val text = "古".repeat(50) + "。" + "前".repeat(20) + "。" + "今".repeat(10)
        assertEquals("前".repeat(20) + "。" + "今".repeat(10), ZenzContext.extract(text, ""))
    }

    @Test
    fun `extract falls back to the raw tail when the aligned suffix would be too short`() {
        // 61 chars: the only sentence start inside the cap leaves 10 chars,
        // so the raw last-60 (most of the previous sentence + head) is kept.
        val text = "古".repeat(50) + "。" + "今".repeat(10)
        assertEquals("古".repeat(49) + "。" + "今".repeat(10), ZenzContext.extract(text, ""))
    }

    @Test
    fun `extract treats a newline as a sentence boundary`() {
        val text = "古".repeat(50) + "\n" + "前".repeat(20) + "\n" + "今".repeat(10)
        assertEquals("前".repeat(20) + "\n" + "今".repeat(10), ZenzContext.extract(text, ""))
    }

    @Test
    fun `extract never returns more than MAX_CHARS`() {
        val cases = listOf(
            "文。".repeat(100),
            "あ".repeat(61),
            ("短い文です。" + "次".repeat(80)),
            ("頭" + "。".repeat(100)),
        )
        for (c in cases) {
            assertTrue(ZenzContext.extract(c, "").length <= ZenzContext.MAX_CHARS)
        }
    }
}
