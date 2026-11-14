package space.manus.nacre.ai

import org.junit.Assert.*
import org.junit.Test

class KanaUtilsTest {

    // ── hiraganaToKatakana ──────────────────────────────────────────────

    @Test
    fun `hiragana converts to katakana`() {
        assertEquals("ミズヲクダサイ", KanaUtils.hiraganaToKatakana("みずをください"))
        assertEquals("カイギハゴゼンクジハンカラデス", KanaUtils.hiraganaToKatakana("かいぎはごぜんくじはんからです"))
    }

    @Test
    fun `small kana and dakuten convert`() {
        assertEquals("ァィゥェォッャュョ", KanaUtils.hiraganaToKatakana("ぁぃぅぇぉっゃゅょ"))
        assertEquals("ガギグゲゴパピプペポ", KanaUtils.hiraganaToKatakana("がぎぐげごぱぴぷぺぽ"))
        assertEquals("ヰヱヲン", KanaUtils.hiraganaToKatakana("ゐゑをん"))
        assertEquals("ヶ", KanaUtils.hiraganaToKatakana("ゖ"))
    }

    @Test
    fun `non hiragana passes through unchanged`() {
        assertEquals("カタカナ", KanaUtils.hiraganaToKatakana("カタカナ"))
        assertEquals("漢字ABC123ー・", KanaUtils.hiraganaToKatakana("漢字ABC123ー・"))
        assertEquals("", KanaUtils.hiraganaToKatakana(""))
    }

    @Test
    fun `mixed text converts only hiragana`() {
        assertEquals("今日ハ会議デス", KanaUtils.hiraganaToKatakana("今日は会議です"))
    }

    // ── ZenzOutput.sanitize ─────────────────────────────────────────────

    @Test
    fun `sanitize accepts a normal conversion`() {
        assertEquals(
            "水をください",
            ZenzOutput.sanitize("水をください", "ミズヲクダサイ", "みずをください"),
        )
    }

    @Test
    fun `sanitize rejects blank and null`() {
        assertNull(ZenzOutput.sanitize(null, "ミズヲクダサイ", "みずをください"))
        assertNull(ZenzOutput.sanitize("", "ミズヲクダサイ", "みずをください"))
        assertNull(ZenzOutput.sanitize("   ", "ミズヲクダサイ", "みずをください"))
    }

    @Test
    fun `sanitize rejects output equal to the reading`() {
        assertNull(ZenzOutput.sanitize("ミズヲクダサイ", "ミズヲクダサイ", "みずをください"))
        assertNull(ZenzOutput.sanitize("みずをください", "ミズヲクダサイ", "みずをください"))
    }

    @Test
    fun `sanitize truncates at control char leftovers`() {
        // Model ran past its stop token into a hallucinated next conversion:
        // everything from the first U+EE00..U+EE07 control char is dropped.
        val raw = "水をください\uEE02の\uEE00カンソウデス"
        assertEquals("水をください", ZenzOutput.sanitize(raw, "ミズヲクダサイ", "みずをください"))
    }

    @Test
    fun `sanitize rejects output starting with a control char`() {
        val raw = "\uEE00ミズヲクダサイ\uEE01水をください"
        assertNull(ZenzOutput.sanitize(raw, "ミズヲクダサイ", "みずをください"))
    }
}
