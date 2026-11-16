package space.manus.nacre.ime.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * isSameKeyBounce() — the QWERTY key-delivery guard for the ttt/nnn
 * doubling (see RomajiTripleConsonantTest for the layer isolation).
 *
 * Suppression requires the full physical signature of a bounce
 * re-delivery: the spurious cycle starts within
 * [SAME_KEY_BOUNCE_MAX_GAP_MS] of the previous accepted release AND
 * lasts under [SAME_KEY_BOUNCE_MAX_PRESS_MS]. Speed alone never
 * suppresses: a deliberate re-tap may LAND quickly after the
 * previous release, but it is held for a real duration; a second
 * finger may land before the first lifts (overlap). Both are real
 * keystrokes in exactly the ttt/nnn runs this guard protects, and
 * eating one would be worse than the doubling.
 *
 * All times are pointer-event times (uptimeMillis), so the cases
 * below are stated in event time, not handler time.
 */
class SameKeyBounceTest {

    @Test
    fun `no previous tap is never a bounce`() {
        assertFalse(isSameKeyBounce(lastReleaseMs = 0L, downMs = 1_000L, upMs = 1_005L))
        assertFalse(isSameKeyBounce(lastReleaseMs = -1L, downMs = 1_000L, upMs = 1_005L))
    }

    @Test
    fun `an immediate and fleeting cycle is a bounce`() {
        // Real tap released at 1000; spurious cycles right after it:
        assertTrue(isSameKeyBounce(lastReleaseMs = 1_000L, downMs = 1_000L, upMs = 1_000L))
        assertTrue(isSameKeyBounce(lastReleaseMs = 1_000L, downMs = 1_005L, upMs = 1_013L))
        assertTrue(
            isSameKeyBounce(
                lastReleaseMs = 1_000L,
                downMs = 1_000L + SAME_KEY_BOUNCE_MAX_GAP_MS - 1,
                upMs = 1_000L + SAME_KEY_BOUNCE_MAX_GAP_MS - 1 + SAME_KEY_BOUNCE_MAX_PRESS_MS - 1,
            ),
        )
    }

    @Test
    fun `a fast but real press after the release is not a bounce`() {
        // The gate's counterexample: a deliberate second tap lands
        // 15ms after the first release but is HELD like a real press.
        assertFalse(isSameKeyBounce(lastReleaseMs = 1_000L, downMs = 1_015L, upMs = 1_115L))
        // The gate's round-2 counterexample: lands 15ms after the
        // release AND lasts only 15ms — still a real press: the gap
        // alone already places it outside the bounce signature.
        assertFalse(isSameKeyBounce(lastReleaseMs = 1_000L, downMs = 1_015L, upMs = 1_030L))
        // Even a short deliberate tap (30ms) is a real press.
        assertFalse(isSameKeyBounce(lastReleaseMs = 1_000L, downMs = 1_005L, upMs = 1_035L))
        // Press exactly at the duration bound is real.
        assertFalse(
            isSameKeyBounce(
                lastReleaseMs = 1_000L,
                downMs = 1_005L,
                upMs = 1_005L + SAME_KEY_BOUNCE_MAX_PRESS_MS,
            ),
        )
    }

    @Test
    fun `a fleeting cycle that starts too late is not a bounce`() {
        assertFalse(
            isSameKeyBounce(
                lastReleaseMs = 1_000L,
                downMs = 1_000L + SAME_KEY_BOUNCE_MAX_GAP_MS,
                upMs = 1_000L + SAME_KEY_BOUNCE_MAX_GAP_MS + 5L,
            ),
        )
        assertFalse(isSameKeyBounce(lastReleaseMs = 1_000L, downMs = 1_060L, upMs = 1_065L))
        assertFalse(isSameKeyBounce(lastReleaseMs = 1_000L, downMs = 2_000L, upMs = 2_005L))
    }

    @Test
    fun `an overlapping second finger press is never suppressed`() {
        // Two-finger alternation on the same key: the second DOWN
        // lands while the first finger is still down — before the
        // first tap's release timestamp (negative gap).
        assertFalse(isSameKeyBounce(lastReleaseMs = 1_000L, downMs = 990L, upMs = 1_050L))
        assertFalse(isSameKeyBounce(lastReleaseMs = 1_000L, downMs = 500L, upMs = 560L))
    }

    @Test
    fun `a missing release event time is never a bounce`() {
        // If the gesture ended without an observed release change,
        // the caller passes upMs = 0: the press duration is negative
        // and the tap must be accepted, never swallowed.
        assertFalse(isSameKeyBounce(lastReleaseMs = 1_000L, downMs = 1_005L, upMs = 0L))
    }

    @Test
    fun `the bounds stay far below a deliberate tap cycle`() {
        // Guard the guard: the combined signature (gap + press) must
        // stay well under the fastest deliberate re-tap cycle, or
        // legitimate fast ttt/nnn typing gets eaten.
        assertTrue(SAME_KEY_BOUNCE_MAX_GAP_MS in 8L..15L)
        assertTrue(SAME_KEY_BOUNCE_MAX_PRESS_MS in 8L..15L)
        assertTrue(SAME_KEY_BOUNCE_MAX_GAP_MS + SAME_KEY_BOUNCE_MAX_PRESS_MS < 30L)
    }
}
