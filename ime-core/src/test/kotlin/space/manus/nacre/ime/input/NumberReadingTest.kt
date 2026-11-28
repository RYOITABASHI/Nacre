package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberReadingTest {

    private fun v(kana: String): Long? = NumberReading.parse(kana)?.value

    @Test
    fun compositeNumbers() {
        assertEquals(25L, v("にじゅうご"))
        assertEquals(20L, v("にじゅう"))
        assertEquals(12L, v("じゅうに"))
        assertEquals(11L, v("じゅういち"))
        assertEquals(1200L, v("せんにひゃく"))
        assertEquals(2026L, v("にせんにじゅうろく"))
        assertEquals(300L, v("さんびゃく"))
        assertEquals(600L, v("ろっぴゃく"))
        assertEquals(800L, v("はっぴゃく"))
        assertEquals(3000L, v("さんぜん"))
        assertEquals(8000L, v("はっせん"))
        assertEquals(450L, v("よんひゃくごじゅう"))
        assertEquals(120L, v("ひゃくにじゅう"))
        assertEquals(12000L, v("いちまんにせん"))
        assertEquals(5000L, v("ごせん"))
        assertEquals(170L, v("ひゃくななじゅう"))
        assertEquals(97L, v("きゅうじゅうしち"))
        assertEquals(300_000_000L, v("さんおく"))
        assertEquals(200L, v("にひゃく"))
        assertEquals(900L, v("きゅうひゃく"))
    }

    @Test
    fun geminatedEndingsBeforeCounters() {
        val p = NumberReading.parse("にじゅういっ")!!
        assertEquals(21L, p.value)
        assertEquals(true, p.geminated)
        assertEquals(30L, v("さんじゅっ"))
        assertEquals(10L, v("いちじっ"))
        assertEquals(16L, v("じゅうろっ"))
    }

    @Test
    fun nonNumbersAndSingleNumeralsAreRejected() {
        assertNull(v("に"))          // single numeral: the dictionary's job
        assertNull(v("じゅう"))
        assertNull(v("ご"))
        assertNull(v("にく"))        // 肉, not 2+9
        assertNull(v("しご"))        // 死語 / 私語: two bare digits are not a number
        assertNull(v("ごはん"))
        assertNull(v("せんせい"))
        assertNull(v("にじゅうの"))
        assertNull(v("いっ"))
        assertNull(v("じゅっぷん"))  // the counter is not part of the number
        assertNull(v("ごぜん"))      // 午前: ぜん only follows さん
        assertNull(v("ごぜんくじ"))
        assertNull(v("にびゃく"))    // びゃく only follows さん
        assertNull(v("にぴゃく"))
    }

    @Test
    fun kanjiAndMixedRendering() {
        assertEquals("二十五", NumberReading.toKanji(25))
        assertEquals("千二百", NumberReading.toKanji(1200))
        assertEquals("二千二十六", NumberReading.toKanji(2026))
        assertEquals("一万二千", NumberReading.toKanji(12000))
        assertEquals("十", NumberReading.toKanji(10))
        assertEquals("三億", NumberReading.toKanji(300_000_000))
        assertEquals("1万2000", NumberReading.toMixed(12000))
        assertNull(NumberReading.toMixed(9999))
    }
}
