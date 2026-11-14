package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import space.manus.nacre.ai.PostProcessor

/**
 * JVM tests for [VoiceSegmentFinalizer]: the segment finalize/preview
 * sequencing behind the Whisper live preview. Buffers are cumulative and
 * append-only, exactly as WhisperService delivers them.
 */
class VoiceSegmentFinalizerTest {

    private lateinit var finalizer: VoiceSegmentFinalizer

    @Before
    fun setUp() {
        finalizer = VoiceSegmentFinalizer(PostProcessor())
    }

    @Test
    fun `first segment is preview only`() {
        val step = finalizer.onBuffer("今日は暑いです")
        assertNull(step.finalizeText)
        assertEquals("今日は暑いです", step.previewText)
    }

    @Test
    fun `second segment finalizes the first`() {
        finalizer.onBuffer("今日は暑いです")
        val step = finalizer.onBuffer("今日は暑いです明日も晴れます")
        assertEquals("今日は暑いです", step.finalizeText)
        assertEquals("明日も晴れます", step.previewText)
    }

    @Test
    fun `unchanged buffer finalizes nothing`() {
        finalizer.onBuffer("今日は暑いです")
        val step = finalizer.onBuffer("今日は暑いです")
        assertNull(step.finalizeText)
        assertEquals("今日は暑いです", step.previewText)
    }

    @Test
    fun `correction inside one segment resolves in preview`() {
        val step = finalizer.onBuffer("火曜日じゃなくて水曜日です")
        assertNull(step.finalizeText)
        assertEquals("水曜日です", step.previewText)
    }

    @Test
    fun `open cue holds finalization until the replacement arrives`() {
        finalizer.onBuffer("火曜日じゃなくて")
        val held = finalizer.onBuffer("火曜日じゃなくて水曜日です")
        assertNull(held.finalizeText)
        assertEquals("水曜日です", held.previewText)

        val next = finalizer.onBuffer("火曜日じゃなくて水曜日ですに会議があります")
        assertEquals("水曜日です", next.finalizeText)
        assertEquals("に会議があります", next.previewText)
    }

    @Test
    fun `cue in new segment can revise the pending segment`() {
        finalizer.onBuffer("会議は火曜日です")
        val step = finalizer.onBuffer("会議は火曜日ですじゃなくて水曜日です")
        assertNull(step.finalizeText)
        assertEquals("水曜日です", step.previewText)
    }

    @Test
    fun `onFinal flushes the flushed tail`() {
        finalizer.onBuffer("今日は")
        val rest = finalizer.onFinal("今日は暑いです")
        assertEquals("今日は暑いです", rest)
    }

    @Test
    fun `onFinal completes an open cue`() {
        finalizer.onBuffer("火曜日じゃなくて")
        val rest = finalizer.onFinal("火曜日じゃなくて水曜日")
        assertEquals("水曜日", rest)
    }

    @Test
    fun `onFinal after incremental finalize returns only the pending tail`() {
        finalizer.onBuffer("今日は暑いです")
        finalizer.onBuffer("今日は暑いです明日も晴れます")
        val rest = finalizer.onFinal("今日は暑いです明日も晴れます")
        assertEquals("明日も晴れます", rest)
    }

    @Test
    fun `onFinal with no prior partial returns the whole transcript`() {
        // Short utterance: the segment only ever appears in the stop flush.
        val rest = finalizer.onFinal("今日は暑いです")
        assertEquals("今日は暑いです", rest)
    }

    @Test
    fun `reset clears session state`() {
        finalizer.onBuffer("今日は暑いです")
        finalizer.reset()
        val step = finalizer.onBuffer("明日も晴れます")
        assertNull(step.finalizeText)
        assertEquals("明日も晴れます", step.previewText)
    }
}
