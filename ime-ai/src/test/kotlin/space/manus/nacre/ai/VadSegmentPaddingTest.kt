package space.manus.nacre.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Window math + history ring for VAD segment padding. The constants
 * under test encode the 2026-11-17 measurement: VAD segment onsets lag
 * the true onset by 0.234–0.276 s, so the pre-roll must exceed 0.276 s.
 */
class VadSegmentPaddingTest {

    private val sr = 16_000

    @Test
    fun paddingCoversMeasuredOnsetLag() {
        // Max measured onset lag was 0.276 s; pre-roll must exceed it.
        assertTrue(VadSegmentPadding.PRE_ROLL_SAMPLES > (0.276 * sr).toInt())
        assertEquals((0.30 * sr).toInt(), VadSegmentPadding.PRE_ROLL_SAMPLES)
        assertEquals((0.10 * sr).toInt(), VadSegmentPadding.POST_ROLL_SAMPLES)
        // Structural cap: sherpa-onnx requires >= 0.1 s of trailing
        // silence before emitting a segment even at the max-speech
        // cap (0.3 s normally). The post-roll must never exceed the
        // smallest such guarantee, or it could eat the next segment.
        assertTrue(VadSegmentPadding.POST_ROLL_SAMPLES <= (0.10 * sr).toInt())
    }

    @Test
    fun basicPaddingExtendsBothSides() {
        val w = VadSegmentPadding.paddedWindow(
            segStart = 16_000, segEnd = 48_000, notBefore = 0, availableEnd = 160_000,
        )
        assertEquals(16_000 - 4_800, w.start)
        assertEquals(48_000 + 1_600, w.end)
    }

    @Test
    fun preRollClampsAtStreamStart() {
        val w = VadSegmentPadding.paddedWindow(
            segStart = 2_000, segEnd = 20_000, notBefore = 0, availableEnd = 160_000,
        )
        assertEquals(0, w.start)
        assertEquals(21_600, w.end)
    }

    @Test
    fun postRollClampsAtAvailableEnd() {
        // Final segment at flush time: only 0.1 s fed past its end.
        val w = VadSegmentPadding.paddedWindow(
            segStart = 16_000, segEnd = 48_000, notBefore = 0, availableEnd = 49_600,
        )
        assertEquals(49_600, w.end)
    }

    @Test
    fun forcedCutSegmentPoppedImmediatelyGetsNoPostRoll() {
        // Segment popped the moment it filled (15 s max speech): no
        // audio past its end has been fed yet.
        val w = VadSegmentPadding.paddedWindow(
            segStart = 0, segEnd = 240_000, notBefore = 0, availableEnd = 240_000,
        )
        assertEquals(0, w.start)
        assertEquals(240_000, w.end)
    }

    @Test
    fun seamTrimsPreRollButKeepsCore() {
        // Previous window (incl. post-roll) ended at 50_000; the next
        // segment's core starts at 52_000. Its pre-roll would reach
        // back to 47_200 — overlapping audio already decoded.
        val w = VadSegmentPadding.paddedWindow(
            segStart = 52_000, segEnd = 80_000, notBefore = 50_000, availableEnd = 160_000,
        )
        assertEquals(50_000, w.start)
        assertEquals(81_600, w.end)
    }

    @Test
    fun contiguousSegmentsNeverOverlapAndNeverGap() {
        // Forced-cut pair: seg1 [0, 240_000], seg2 starts immediately
        // at 240_000. seg1 is drained late (plenty of audio already
        // fed), but endLimit = seg2's start must cap its post-roll to
        // zero so seg2 keeps its full core — the baseline partition.
        val w1 = VadSegmentPadding.paddedWindow(
            segStart = 0, segEnd = 240_000, notBefore = 0, availableEnd = 480_000,
            endLimit = 240_000,
        )
        assertEquals(240_000, w1.end)
        val w2 = VadSegmentPadding.paddedWindow(
            segStart = 240_000, segEnd = 300_000, notBefore = w1.end, availableEnd = 480_000,
        )
        assertEquals(240_000, w2.start)
        assertEquals(301_600, w2.end)
    }

    @Test
    fun endLimitCapsPostRollInsideSilenceGap() {
        // Next segment starts 1_000 samples after this one ends:
        // post-roll (1_600 wanted) is capped at the gap.
        val w = VadSegmentPadding.paddedWindow(
            segStart = 16_000, segEnd = 48_000, notBefore = 0, availableEnd = 160_000,
            endLimit = 49_000,
        )
        assertEquals(49_000, w.end)
    }

    @Test
    fun endLimitAtSegEndMeansNoPostRoll() {
        val w = VadSegmentPadding.paddedWindow(
            segStart = 16_000, segEnd = 48_000, notBefore = 0, availableEnd = 160_000,
            endLimit = 48_000,
        )
        assertEquals(48_000, w.end)
    }

    @Test
    fun endLimitNeverCapsInsideOwnCore() {
        // A (theoretically impossible) endLimit inside the segment
        // must not truncate the segment itself.
        val w = VadSegmentPadding.paddedWindow(
            segStart = 16_000, segEnd = 48_000, notBefore = 0, availableEnd = 160_000,
            endLimit = 30_000,
        )
        assertEquals(48_000, w.end)
    }

    @Test
    fun fullyCoveredSegmentIsMarkedForSkip() {
        // A short segment entirely inside the previous post-roll.
        val w = VadSegmentPadding.paddedWindow(
            segStart = 241_000, segEnd = 244_000, notBefore = 244_000, availableEnd = 480_000,
        )
        assertTrue(w.coversWholeSegment(244_000))
        assertTrue(w.size >= 0)
    }

    @Test
    fun zeroPaddingIsIdentity() {
        val w = VadSegmentPadding.paddedWindow(
            segStart = 1_000, segEnd = 9_000, notBefore = 0, availableEnd = 100_000,
            preRoll = 0, postRoll = 0,
        )
        assertEquals(1_000, w.start)
        assertEquals(9_000, w.end)
    }

    // ---- AudioHistoryBuffer ----

    @Test
    fun historySliceReturnsAppendedAudio() {
        val h = AudioHistoryBuffer(capacitySamples = 100)
        h.append(FloatArray(50) { it.toFloat() })
        h.append(FloatArray(50) { (it + 50).toFloat() })
        val s = h.slice(10, 60)!!
        assertEquals(50, s.size)
        assertEquals(10f, s[0], 0f)
        assertEquals(59f, s[49], 0f)
        assertEquals(100, h.totalFed)
    }

    @Test
    fun historySliceRejectsEvictedAndUnfedRanges() {
        val h = AudioHistoryBuffer(capacitySamples = 100)
        h.append(FloatArray(250) { it.toFloat() })
        assertEquals(150, h.oldestRetained)
        assertNull(h.slice(0, 10)) // evicted
        assertNull(h.slice(149, 160)) // partially evicted
        assertNull(h.slice(240, 260)) // beyond fed
        val tail = h.slice(150, 250)!!
        assertEquals(150f, tail[0], 0f)
        assertEquals(249f, tail[99], 0f)
    }

    @Test
    fun historyAppendLargerThanCapacityKeepsTail() {
        val h = AudioHistoryBuffer(capacitySamples = 100)
        h.append(FloatArray(30) { 1000f + it })
        h.append(FloatArray(250) { it.toFloat() })
        assertEquals(280, h.totalFed)
        assertEquals(180, h.oldestRetained)
        // Absolute 180 == second chunk's index 150.
        val s = h.slice(180, 280)!!
        assertEquals(150f, s[0], 0f)
        assertEquals(249f, s[99], 0f)
    }

    @Test
    fun historyResetRestartsIndexing() {
        val h = AudioHistoryBuffer(capacitySamples = 100)
        h.append(FloatArray(80) { it.toFloat() })
        h.reset()
        assertEquals(0, h.totalFed)
        h.append(FloatArray(10) { 500f + it })
        val s = h.slice(0, 10)!!
        assertEquals(500f, s[0], 0f)
        assertNull(h.slice(10, 20))
    }

    @Test
    fun historySliceHandlesRingWrapAround() {
        val h = AudioHistoryBuffer(capacitySamples = 64)
        h.append(FloatArray(100) { it.toFloat() })
        // Retained window is [36, 100); slice crossing the wrap point.
        val s = h.slice(60, 100)!!
        for (i in s.indices) assertEquals((60 + i).toFloat(), s[i], 0f)
    }
}
