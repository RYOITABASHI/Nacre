package space.manus.nacre.ai

/**
 * Pre/post padding for VAD segments before they reach the recognizer.
 *
 * Measured defect (2026-11-17 real-world voice evaluation,
 * research_notes/nacre-voice-realworld-eval-20261117): on a 52.6 s
 * 12-utterance concatenation, Silero VAD v6 segments started uniformly
 * 0.234–0.276 s AFTER the true utterance onset and ran ~0.4 s short
 * overall, clipping word beginnings ("マレーシア" → "レーシア") and
 * tails. The VAD-path CER was worse than direct whole-audio decoding in
 * every condition (Reazon clean: 6.96% vs 3.48%). The detector itself
 * segments correctly (12 utterances → 12 segments, stable under noise);
 * only the window handed to the recognizer is too tight. This is purely
 * a windowing fix — the VAD model and its thresholds are untouched.
 *
 * Chosen padding:
 *  - [PRE_ROLL_SAMPLES] = 0.30 s: covers the maximum measured onset lag
 *    (0.276 s) with margin, at the top of the report's recommended
 *    0.25–0.3 s range. A smaller value would leave the worst-case
 *    onsets clipped.
 *  - [POST_ROLL_SAMPLES] = 0.10 s: the measured tail shortfall is
 *    typically 0.096–0.171 s, so 0.10 s recovers the bulk of the
 *    clipped tail. The value is capped by a structural limit, not by
 *    the measurement: sherpa-onnx only emits a segment after a
 *    trailing silence of at least min_silence_duration (0.3 s here),
 *    EXCEPT at the max-speech-duration cap, where it drops the
 *    requirement to 0.1 s (voice-activity-detector.cc,
 *    new_min_silence_duration_s_ = 0.1 in the pinned 1.12.34). A
 *    post-roll of 0.1 s therefore always lands inside the silence gap
 *    the VAD itself required, and can never reach the next segment's
 *    speech. A longer post-roll (0.25 s was tried in review) could
 *    overrun a cap-mode gap and — combined with the seam watermark —
 *    start the next segment mid-word: worse than the baseline.
 *
 * Seam rules: a window never starts before `notBefore` — the end of
 * the previously decoded window — and never ends past the next
 * segment's start (`endLimit`, when the successor is already queued).
 * Audio is therefore never decoded twice, and no window can consume
 * another segment's core.
 */
object VadSegmentPadding {

    /** 0.30 s at 16 kHz — see class doc for the measurement behind it. */
    const val PRE_ROLL_SAMPLES = 4_800

    /** 0.10 s at 16 kHz — see class doc for why it cannot be longer. */
    const val POST_ROLL_SAMPLES = 1_600

    /** Absolute sample window [start, end) to hand to the recognizer. */
    data class Window(val start: Int, val end: Int) {
        val size: Int get() = end - start

        /** True when [start] has reached the segment's own end, i.e. the
         *  whole segment was already covered by the previous window and
         *  there is nothing new to decode. */
        fun coversWholeSegment(segEnd: Int): Boolean = start >= segEnd
    }

    /**
     * Compute the padded decode window for one VAD segment.
     *
     * @param segStart absolute sample index of the segment start
     *   (sherpa-onnx `SpeechSegment.start`, relative to stream start /
     *   last VAD reset).
     * @param segEnd absolute sample index just past the segment's last
     *   sample (`segStart + segment.samples.size`).
     * @param notBefore end of the previously decoded window; the new
     *   window never starts before this (seam de-duplication). It may
     *   push the start past [segStart] — that audio was already decoded
     *   as the previous segment's post-roll.
     * @param availableEnd total samples fed so far; the window never
     *   extends past the audio that actually exists (matters for a
     *   forced-cut segment popped before its post-roll has been fed,
     *   and for the final segment at flush time).
     * @param endLimit absolute index the window must not extend past —
     *   the next segment's start, when known. Guards the forced-cut
     *   seam: post-roll must not eat the following segment's core,
     *   because the seam watermark would then start that segment
     *   mid-word. Never caps inside this segment's own core.
     */
    fun paddedWindow(
        segStart: Int,
        segEnd: Int,
        notBefore: Int,
        availableEnd: Int,
        preRoll: Int = PRE_ROLL_SAMPLES,
        postRoll: Int = POST_ROLL_SAMPLES,
        endLimit: Int = Int.MAX_VALUE,
    ): Window {
        require(segStart >= 0 && segEnd >= segStart) {
            "invalid segment [$segStart, $segEnd)"
        }
        val endCap = maxOf(endLimit, segEnd)
        val end = minOf(availableEnd, segEnd + postRoll, endCap)
        var start = maxOf(0, segStart - preRoll)
        if (notBefore > start) {
            // Trim the pre-roll at the seam; never push past the
            // segment's own end (that case means "already covered").
            start = minOf(notBefore, segEnd)
        }
        return Window(start, maxOf(end, start))
    }
}

/**
 * Ring buffer of recently fed audio, indexed by absolute sample
 * position, so [SherpaRecognizer] can slice out the padding around a
 * VAD segment (the VAD's own segment payload contains only the samples
 * between its detected boundaries — the pre-roll audio before the
 * detected onset exists only here).
 *
 * Capacity must cover the oldest audio a drain can still ask for:
 * a segment is at most the VAD's max speech duration (15 s) long and
 * is drained within a fraction of a second of its end in streaming
 * mode; the single-shot path feeds a whole recording (capped at 30 s
 * by `WhisperService.recordAudioUntilSilence`) in one call before
 * draining. [SherpaRecognizer] therefore uses 36 s.
 */
class AudioHistoryBuffer(private val capacitySamples: Int) {

    init {
        require(capacitySamples > 0) { "capacity must be positive" }
    }

    private val ring = FloatArray(capacitySamples)

    /** Total samples appended since [reset]; also the absolute index
     *  just past the newest sample. */
    var totalFed: Int = 0
        private set

    /** Absolute index of the oldest sample still retained. */
    val oldestRetained: Int get() = maxOf(0, totalFed - capacitySamples)

    fun append(samples: FloatArray) {
        // samples[j] lands at absolute index totalFed + j. When the
        // chunk is larger than the whole ring, only its tail survives.
        val from = if (samples.size >= capacitySamples) samples.size - capacitySamples else 0
        for (j in from until samples.size) {
            ring[(totalFed + j) % capacitySamples] = samples[j]
        }
        totalFed += samples.size
    }

    /**
     * Copy out the absolute range [start, end), or return null when any
     * part of it has already been evicted (or was never fed). Callers
     * fall back to the VAD segment's own samples on null.
     */
    fun slice(start: Int, end: Int): FloatArray? {
        if (start < 0 || end < start || end > totalFed) return null
        if (start < oldestRetained) return null
        val out = FloatArray(end - start)
        for (i in out.indices) {
            out[i] = ring[(start + i) % capacitySamples]
        }
        return out
    }

    fun reset() {
        totalFed = 0
    }
}
