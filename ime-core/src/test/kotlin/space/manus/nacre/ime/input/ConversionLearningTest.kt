package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ConversionLearning] — the per-(reading, surface)
 * selection-learning store that feeds the conversion/prediction ranking
 * boost in [NacreDictionary].
 */
class ConversionLearningTest {

    private fun newMap() = HashMap<String, ConversionLearning.Entry>()

    @Test
    fun recordIncrementsCountAndTracksLastSeq() {
        val map = newMap()
        val e1 = ConversionLearning.record(map, "あい", "愛", seq = 1)
        assertNotNull(e1)
        assertEquals(1, e1!!.count)
        assertEquals(1L, e1.lastSeq)

        val e2 = ConversionLearning.record(map, "あい", "愛", seq = 7)
        assertEquals(2, e2!!.count)
        assertEquals(7L, e2.lastSeq)
        assertEquals(1, map.size)

        // A different surface for the same reading is a separate entry.
        ConversionLearning.record(map, "あい", "相", seq = 8)
        assertEquals(2, map.size)
    }

    @Test
    fun recordRejectsBlankAndSanitizesDelimiters() {
        val map = newMap()
        assertNull(ConversionLearning.record(map, "", "愛", seq = 1))
        assertNull(ConversionLearning.record(map, "あい", "  ", seq = 1))
        assertTrue(map.isEmpty())

        val e = ConversionLearning.record(map, "あ\tい", "愛\n情", seq = 1)
        assertNotNull(e)
        assertEquals("あ い", e!!.reading)
        assertEquals("愛 情", e.surface)
        // The serialized form must stay one line with exactly 4 fields.
        val line = ConversionLearning.serialize(map.values)
        assertEquals(1, line.split('\n').size)
        assertEquals(4, line.split('\t').size)
    }

    @Test
    fun countBoostUsesSaturatingTable() {
        assertEquals(0, ConversionLearning.countBoost(0))
        assertEquals(2000, ConversionLearning.countBoost(1))
        assertEquals(3500, ConversionLearning.countBoost(2))
        assertEquals(4500, ConversionLearning.countBoost(3))
        assertEquals(5200, ConversionLearning.countBoost(4))
        assertEquals(5500, ConversionLearning.countBoost(5))
        assertEquals(5500, ConversionLearning.countBoost(100))
    }

    @Test
    fun recencyBoostDecaysWithSelectionAge() {
        val entry = ConversionLearning.Entry("あい", "愛", count = 1, lastSeq = 100)
        assertEquals(1200, ConversionLearning.recencyBoost(entry, currentSeq = 100))
        assertEquals(1200, ConversionLearning.recencyBoost(entry, currentSeq = 110))
        assertEquals(800, ConversionLearning.recencyBoost(entry, currentSeq = 111))
        assertEquals(800, ConversionLearning.recencyBoost(entry, currentSeq = 150))
        assertEquals(400, ConversionLearning.recencyBoost(entry, currentSeq = 151))
        assertEquals(400, ConversionLearning.recencyBoost(entry, currentSeq = 300))
        assertEquals(0, ConversionLearning.recencyBoost(entry, currentSeq = 301))
        assertEquals(0, ConversionLearning.recencyBoost(null, currentSeq = 100))
        // A future lastSeq (clock skew in stored data) never goes negative.
        assertEquals(1200, ConversionLearning.recencyBoost(entry, currentSeq = 50))
    }

    @Test
    fun boostCombinesFrequencyAndRecencyAndIsBounded() {
        val fresh = ConversionLearning.Entry("あい", "愛", count = 5, lastSeq = 100)
        // 5500 (count) + 1200 (fresh) would be 6700 — under the cap.
        assertEquals(6700, ConversionLearning.boost(legacyCount = 0, entry = fresh, currentSeq = 100))
        // Stale: recency falls away, frequency remains.
        assertEquals(5500, ConversionLearning.boost(legacyCount = 0, entry = fresh, currentSeq = 10_000))
        // Never exceeds MAX_BOOST regardless of inputs.
        val huge = ConversionLearning.Entry("あい", "愛", count = 1_000_000, lastSeq = 100)
        assertTrue(
            ConversionLearning.boost(legacyCount = 1_000_000, entry = huge, currentSeq = 100) <=
                ConversionLearning.MAX_BOOST,
        )
        // A recent single pick outranks a stale single pick — the property
        // the legacy counter alone could not express.
        val stale = ConversionLearning.Entry("あい", "相", count = 1, lastSeq = 1)
        val recent = ConversionLearning.Entry("あい", "愛", count = 1, lastSeq = 100)
        assertTrue(
            ConversionLearning.boost(0, recent, currentSeq = 100) >
                ConversionLearning.boost(0, stale, currentSeq = 100),
        )
    }

    @Test
    fun effectiveCountKeepsLegacyHistory() {
        val entry = ConversionLearning.Entry("あい", "愛", count = 2, lastSeq = 5)
        assertEquals(9, ConversionLearning.effectiveCount(legacyCount = 9, entry = entry))
        assertEquals(2, ConversionLearning.effectiveCount(legacyCount = 0, entry = entry))
        assertEquals(9, ConversionLearning.effectiveCount(legacyCount = 9, entry = null))
        assertEquals(0, ConversionLearning.effectiveCount(legacyCount = 0, entry = null))
        // Legacy-only users keep their old boost value exactly.
        assertEquals(
            ConversionLearning.countBoost(9),
            ConversionLearning.boost(legacyCount = 9, entry = null, currentSeq = 123),
        )
    }

    @Test
    fun zenzPromotionThresholdIsThree() {
        assertFalse(ConversionLearning.shouldPromoteOverZenz(0))
        assertFalse(ConversionLearning.shouldPromoteOverZenz(1))
        assertFalse(ConversionLearning.shouldPromoteOverZenz(2))
        assertTrue(ConversionLearning.shouldPromoteOverZenz(3))
        assertTrue(ConversionLearning.shouldPromoteOverZenz(10))
        assertEquals(3, ConversionLearning.PROMOTE_OVER_ZENZ_MIN_COUNT)
    }

    @Test
    fun serializeParseRoundTrips() {
        val map = newMap()
        ConversionLearning.record(map, "あい", "愛", seq = 3)
        ConversionLearning.record(map, "あい", "愛", seq = 9)
        ConversionLearning.record(map, "うみ", "海", seq = 5)

        val parsed = ConversionLearning.parse(ConversionLearning.serialize(map.values))
        assertEquals(map.size, parsed.size)
        for ((k, v) in map) {
            assertEquals(v, parsed[k])
        }
        // Freshest first in the serialized form.
        val lines = ConversionLearning.serialize(map.values).split('\n')
        assertTrue(lines[0].contains("愛"))
    }

    @Test
    fun parseSkipsMalformedLines() {
        val parsed = ConversionLearning.parse(
            "あい\t愛\t2\t9\n" +
                "broken line without tabs\n" +
                "うみ\t海\tnotanumber\t5\n" +
                "そら\t空\t1\tnotaseq\n" +
                "\t\t3\t4\n" +
                "かぜ\t風\t0\t2\n" +
                "\n",
        )
        assertEquals(1, parsed.size)
        assertEquals(2, parsed[ConversionLearning.key("あい", "愛")]!!.count)
    }

    @Test
    fun pruneKeepsStrongestAndFreshest() {
        val map = newMap()
        // Fill past the cap with weak one-time entries, then add a strong one.
        for (i in 0 until ConversionLearning.MAX_ENTRIES) {
            map[ConversionLearning.key("よみ$i", "表$i")] =
                ConversionLearning.Entry("よみ$i", "表$i", count = 1, lastSeq = i.toLong())
        }
        ConversionLearning.record(map, "あい", "愛", seq = 1)
        ConversionLearning.record(map, "あい", "愛", seq = 2)
        ConversionLearning.record(map, "あい", "愛", seq = ConversionLearning.MAX_ENTRIES + 100L)
        assertTrue(map.size <= ConversionLearning.MAX_ENTRIES)
        // The repeatedly-selected entry survives; the oldest one-timer is gone.
        assertNotNull(map[ConversionLearning.key("あい", "愛")])
        assertNull(map[ConversionLearning.key("よみ0", "表0")])
    }
}
