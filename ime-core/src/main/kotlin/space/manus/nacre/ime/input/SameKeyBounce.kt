package space.manus.nacre.ime.input

/**
 * Same-key tap bounce guard for the QWERTY surface (KeyView).
 *
 * The owner's on-device report (2026-11-17): on QWERTY, typing っ
 * tends to produce っっ, and ん-runs double the same way (ttt / nnn
 * keystroke runs). Layer isolation (RomajiTripleConsonantTest)
 * proved the kana layer innocent: fed each keystroke exactly once,
 * the romaji buffer converts exactly as Gboard/Mozc do for every
 * standard word and literal run — the doubling appears only when
 * one keystroke of the run is DELIVERED twice, i.e. the romaji
 * buffer itself gains a letter. Touch controllers can re-deliver a
 * tap as a spurious second down/up cycle right after the real
 * release (contact bounce); in a run of identical consonants that
 * extra letter is exactly a doubled っ/ん, while in ordinary text
 * it is far less visible — matching the report's shape.
 *
 * A bounce is identified by its physical signature, not by speed
 * alone: the spurious cycle BEGINS almost immediately after the
 * real release AND is itself fleeting — the finger was never
 * really there. A deliberate re-tap, however fast, is a real press
 * with a real duration, and two-finger alternation on one key
 * overlaps (the second DOWN lands before the first release). Both
 * must always pass: eating a deliberate letter in a ttt/nnn run
 * would be worse than the doubling. So suppression requires BOTH:
 *
 *  - the tap's DOWN begins within [SAME_KEY_BOUNCE_MAX_GAP_MS] of
 *    the same key's previous accepted tap release, and
 *  - the tap's own press duration is under
 *    [SAME_KEY_BOUNCE_MAX_PRESS_MS].
 *
 * The bounds are set where an intentional tap is not physically
 * plausible: a deliberate capacitive tap registers contact for
 * several tens of ms even when feather-light, and a deliberate
 * re-tap cycle (release → next contact) adds its own travel time
 * on top. A sub-12ms contact arriving sub-12ms after the previous
 * release is a >40Hz same-key cycle with micro-contacts — the
 * signature of a controller re-delivery, not of a finger.
 *
 * All timestamps are pointer-event times (uptimeMillis, one
 * monotonic clock), never handler execution times, so a busy UI
 * thread cannot compress a legitimate gap into the window.
 */
internal const val SAME_KEY_BOUNCE_MAX_GAP_MS: Long = 12L
internal const val SAME_KEY_BOUNCE_MAX_PRESS_MS: Long = 12L

/**
 * True when a tap spanning [downMs]..[upMs] (pointer-event times)
 * is a bounce re-delivery of the tap released at [lastReleaseMs]
 * on the same key. [lastReleaseMs] <= 0 means no accepted tap yet
 * — never a bounce. A negative gap (overlap: the new DOWN precedes
 * the recorded release) is a real second-finger press, and a press
 * of real duration is a deliberate tap — neither is ever a bounce.
 */
internal fun isSameKeyBounce(
    lastReleaseMs: Long,
    downMs: Long,
    upMs: Long,
    maxGapMs: Long = SAME_KEY_BOUNCE_MAX_GAP_MS,
    maxPressMs: Long = SAME_KEY_BOUNCE_MAX_PRESS_MS,
): Boolean {
    if (lastReleaseMs <= 0L) return false
    val gapMs = downMs - lastReleaseMs
    if (gapMs < 0L || gapMs >= maxGapMs) return false
    val pressMs = upMs - downMs
    return pressMs >= 0L && pressMs < maxPressMs
}
