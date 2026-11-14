package space.manus.nacre.ime.input

import space.manus.nacre.ai.PostProcessor

/**
 * Incremental finalizer for Whisper continuous recognition.
 *
 * WhisperService delivers the session's accumulated raw transcript through
 * `IWhisperCallback.onPartialResult` every time a VAD segment completes, and
 * the full text once more through `onResult` when recording stops. Until now
 * the manager ignored the partials ("no preview during recording"), so the
 * user saw nothing until stop, when the whole session landed at once.
 *
 * This class turns the append-only buffer stream into finalize/preview
 * decisions so the manager can show each segment as composing text in the
 * editor as it arrives and commit it once the NEXT segment lands:
 *
 * - The newest segment is the preview (still revisable).
 * - The previous segment is finalized when a new one arrives — finalizing
 *   lags one segment behind on purpose, so a self-correction cue
 *   ("…じゃなくて…") can still revise unfinalized text, exactly like the
 *   SpeechRecognizer path's retroactive-correction handling.
 * - If the new delta contains a correction cue (it may target the pending
 *   segment) or the pending text ends with an open cue (replacement not yet
 *   spoken), finalization holds and the combined text is run through
 *   [PostProcessor.resolveCorrections] — the same resolution the existing
 *   paths apply to a whole utterance.
 * - [onFinal] flushes whatever is still pending when recording stops.
 *
 * Pure Kotlin (the only dependency is the stateless [PostProcessor]) so the
 * sequencing is JVM-testable without Android.
 */
class VoiceSegmentFinalizer(private val postProcessor: PostProcessor) {

    /** One step: [finalizeText] to commit now (null = commit nothing), and
     *  [previewText] to show as the live composing preview. */
    data class Step(val finalizeText: String?, val previewText: String)

    /** Last cumulative buffer seen from the service (append-only). */
    private var lastBuffer: String = ""

    /**
     * Unfinalized text, kept in self-correction-resolved form. May end with
     * an open correction cue whose replacement has not been spoken yet.
     */
    private var pending: String = ""

    fun onBuffer(buffer: String): Step {
        if (buffer.isBlank()) return Step(null, pending)
        val delta = if (lastBuffer.isNotEmpty() && buffer.startsWith(lastBuffer)) {
            buffer.substring(lastBuffer.length)
        } else {
            // Buffer restarted/replaced mid-session (not expected in
            // continuous mode) — treat the whole thing as new material.
            buffer
        }
        lastBuffer = buffer
        if (delta.isEmpty()) return Step(null, pending)

        // A cue in the delta may reach back into the pending segment, and an
        // open cue at the pending tail is completed by this delta. Either
        // way the two must be resolved together before anything finalizes.
        val spanningRisk = containsCue(delta) || containsCue(pending)
        return if (pending.isNotEmpty() && !spanningRisk) {
            val finalize = pending
            pending = delta
            Step(finalizeText = finalize, previewText = pending)
        } else {
            pending = postProcessor.resolveCorrections(pending + delta)
            Step(finalizeText = null, previewText = pending)
        }
    }

    /**
     * Recording stopped: [fullText] is the service's final transcript (last
     * buffer plus the flushed tail). Returns the remaining text to finalize.
     */
    fun onFinal(fullText: String): String {
        val tail = when {
            // No partial ever arrived (short utterance flushed only at
            // stop): the whole final transcript is new material.
            lastBuffer.isEmpty() -> fullText
            fullText.startsWith(lastBuffer) -> fullText.substring(lastBuffer.length)
            // Transcript replaced unexpectedly — cannot diff safely; the
            // pending preview is all we can still account for.
            else -> ""
        }
        val result = postProcessor.resolveCorrections(pending + tail)
        reset()
        return result
    }

    fun reset() {
        lastBuffer = ""
        pending = ""
    }

    private fun containsCue(text: String): Boolean {
        if (text.isEmpty()) return false
        if (JA_CUES.any { text.contains(it) }) return true
        val lower = text.lowercase()
        return EN_CUES.any { lower.contains(it) }
    }

    private companion object {
        // Literal stems of PostProcessor's correction patterns. A false
        // positive only delays finalization by one segment (the text still
        // previews and flushes at stop); a false negative could commit an
        // unresolved correction, so the list errs on the broad side.
        val JA_CUES = listOf(
            "じゃなくて", "ではなく", "じゃない", "違う", "ちがう",
            "間違えた", "訂正", "もとい", "いや",
        )
        val EN_CUES = listOf(
            "no wait", "i mean", "sorry", "actually",
            "correction", "rather", "let me rephrase",
        )
    }
}
