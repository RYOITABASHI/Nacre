package space.manus.nacre.ime.input

/**
 * Tracks the ONE span the post-hoc ⇧ transform may touch: the run of
 * letters typed via the 12-key ABC pad since the last resetting action.
 * Pure state machine — no Android — so the whole contract is
 * unit-testable; [InputEngine] feeds it commits and performs the actual
 * editor rewrite.
 *
 * Why a tracker at all: PR #69 aimed ⇧ at whatever word happened to
 * precede the cursor and derived each step from that word's current
 * case pattern. Pressing ⇧ just to arm the NEXT word rewrote old text,
 * and natural mixed-case words (iPhone, McDonald) were "advanced" to
 * forms the user never asked for, changing letters far from the caret.
 * This tracker makes the behavior boring and predictable instead:
 *
 * - The target is established ONLY by ABC-pad letter commits in this
 *   input session. No target → ⇧ is classic pre-arm, full stop.
 * - The span's ORIGINAL text (as typed, before any transform) and the
 *   text we last wrote are recorded. Every ⇧ step is computed from the
 *   original via [ShiftCaseCycle.formAt] — never re-derived from the
 *   current text — so the cycle always returns to exactly what the
 *   user typed.
 * - Resetting actions (cursor move incl. ◀ ▶, selection change, space /
 *   punctuation / other-character commit, mode switch, field change,
 *   candidate selection, delete) call [reset]; the engine wires those.
 * - Content equality alone cannot prove the span before the cursor is
 *   OUR span (type "a", tap the cursor behind an older "a" elsewhere,
 *   press ⇧ — the texts match but the identity does not). So the span
 *   also carries an absolute anchor: [anchorEnd], the cursor position
 *   immediately after the span, captured once from the editor when the
 *   span starts and then maintained arithmetically (every mutation of
 *   the span goes through this tracker and is length-exact). Selection
 *   updates reported by the editor go through [onSelectionChanged] and
 *   drop the target early on any external move — but notifications are
 *   asynchronous, so they are NOT the authorization: [advance] demands
 *   the LIVE cursor position, read synchronously by the engine at ⇧
 *   time, and fails closed (drop + pre-arm) whenever identity cannot be
 *   proven — unknown anchor, live cursor elsewhere, or span content
 *   that no longer matches [lastWritten]. Never a guess at some other
 *   word.
 */
class PostHocShiftTracker {

    /** The tracked span as originally typed, before any transform; null = no target. */
    var original: String? = null
        private set

    /** What the span contains according to our last write (== [original] at step 0). */
    var lastWritten: String? = null
        private set

    /** Current cycle step (a [ShiftCaseCycle] STEP_* value). */
    var step: Int = ShiftCaseCycle.STEP_ORIGINAL
        private set

    /**
     * Absolute cursor position immediately after the span, or -1 when
     * unknown (the editor could not report its text when the span
     * started). Captured once via [onAnchorKnown]; maintained
     * arithmetically from then on — appends advance it by the committed
     * length, replacements and case rewrites are length-preserving.
     * An unknown anchor is NOT a degraded mode: [advance] fails closed
     * without one, so an unanchored target can never be rewritten.
     */
    var anchorEnd: Int = -1
        private set

    val hasTarget: Boolean get() = original != null

    /** Kill the target. Called on every resetting action. */
    fun reset() {
        original = null
        lastWritten = null
        step = ShiftCaseCycle.STEP_ORIGINAL
        anchorEnd = -1
    }

    /**
     * Record the span's absolute end position, read from the editor by
     * the engine right after the commit that started the span. Ignored
     * without a target.
     */
    fun onAnchorKnown(absoluteCursorAfterSpan: Int) {
        if (hasTarget) anchorEnd = absoluteCursorAfterSpan
    }

    /**
     * A selection update reported by the editor (the service's
     * onUpdateSelection) — an EARLY drop only. Our own commits and
     * rewrites always leave a collapsed cursor exactly at [anchorEnd];
     * anything else — a tap elsewhere in the text, a drag selection,
     * the app moving the cursor — is an external change and drops the
     * target, even when the text at the new position happens to read
     * identically. An UNANCHORED target is dropped by any update: its
     * position is unprovable, our own commit's echo cannot be told from
     * an external move, and letting it linger would let a later letter
     * extend a span whose first letters may no longer be the ones
     * before the cursor. Delivery is asynchronous, so a notification
     * that has not arrived yet proves nothing either way: [advance]
     * re-verifies the live position before any rewrite is authorized.
     */
    fun onSelectionChanged(selStart: Int, selEnd: Int) {
        if (!hasTarget) return
        val anchor = anchorEnd
        if (anchor < 0 || selStart != anchor || selEnd != anchor) reset()
    }

    /**
     * A letter was committed via the ABC pad immediately after the
     * tracked span (or with no span yet): the span grows by [letter].
     * When a transform had already been applied, the text as it stands —
     * our last write plus the new letter — becomes the new original and
     * the cycle restarts from it: everything in the span is still text
     * we wrote, recorded here, never read back and guessed at.
     */
    fun onLetterAppended(letter: String) {
        val extended = (lastWritten ?: "") + letter
        original = extended
        lastWritten = extended
        step = ShiftCaseCycle.STEP_ORIGINAL
        if (anchorEnd >= 0) anchorEnd += letter.length
    }

    /**
     * The ABC tap-cycle replaced the span's last letter with [letter]
     * (the text actually committed, case included). Both the original
     * and the last write end with the letter now on screen, and the
     * cycle restarts from it. With no target (tracker/cycler desync),
     * the replaced letter alone becomes the target — it is, verifiably,
     * a letter just committed via the ABC pad.
     */
    fun onLastLetterReplaced(letter: String) {
        val orig = original
        if (orig.isNullOrEmpty()) {
            onLetterAppended(letter)
            return
        }
        original = orig.dropLast(1) + letter
        lastWritten = (lastWritten ?: orig).dropLast(1) + letter
        step = ShiftCaseCycle.STEP_ORIGINAL
    }

    /**
     * Advance the cycle for one ⇧ press — the authorization point for
     * every rewrite. [textInSpan] is the editor's current content of the
     * tracked span (the [lastWritten].length characters immediately
     * before the cursor) and [liveCursorAfterSpan] the editor's LIVE
     * absolute cursor position, both from the single snapshot the
     * engine reads synchronously at ⇧ time.
     *
     * Returns the text to write into the span, or null when the press
     * must fall back to classic pre-arm. Every verification failure
     * drops the target rather than guessing:
     * - no target;
     * - FAIL CLOSED on identity: no anchor was ever captured, or the
     *   live cursor is not collapsed exactly at [anchorEnd] — the text
     *   before the cursor may read identically to our span and still be
     *   a different, older word (an external move whose asynchronous
     *   selection notification has not arrived yet is caught here);
     * - the stale-span drop: [textInSpan] differs from [lastWritten],
     *   meaning the text changed behind our back;
     * - a span with no cased letter.
     *
     * A non-null result may equal [textInSpan] (a step whose form
     * coincides with the current text, e.g. Capitalize of an
     * already-Capitalized original): the press still counts, the step
     * still advances, and the engine can skip the redundant rewrite.
     */
    fun advance(textInSpan: String, liveCursorAfterSpan: Int): String? {
        val orig = original ?: return null
        val anchor = anchorEnd
        if (anchor < 0 || liveCursorAfterSpan != anchor) {
            reset()
            return null
        }
        if (textInSpan != lastWritten) {
            reset()
            return null
        }
        if (orig.none { it.isUpperCase() || it.isLowerCase() }) {
            reset()
            return null
        }
        step = (step + 1) % ShiftCaseCycle.STEP_COUNT
        val form = ShiftCaseCycle.formAt(orig, step)
        // Case forms of ABC letters are length-preserving, so the anchor
        // stays exact; if a form ever changed the span's length, the
        // anchor is no longer derivable — drop it (the content check
        // above remains as the guard) rather than track a wrong position.
        if (form.length != lastWritten?.length) anchorEnd = -1
        lastWritten = form
        return form
    }
}
