package space.manus.nacre.ime.foldable

import org.junit.Assert.assertEquals
import org.junit.Test

private fun decideLayout(
    widthDp: Float,
    isSubDisplay: Boolean,
    mainDisplayMode: LayoutMode,
    subDisplayMode: LayoutMode,
): LayoutMode = LayoutSelector.decideLayout(widthDp, isSubDisplay, mainDisplayMode, subDisplayMode)

/**
 * The pure layout decision behind fold/unfold handling (S5 fix).
 *
 * The fix for the fold view-recreation bug rests on two premises pinned here:
 *  1. Re-picking the layout for the NEW window width is all a fold needs —
 *     [decideLayout] returns the cover layout for cover widths and the main
 *     layout for main widths, honouring each display's own preference, so an
 *     epoch-bump re-pick (no view recreation) lands on the right keyboard.
 *  2. Nothing about the decision depends on view lifecycle — composing text,
 *     candidates and pad state live outside it (InputEngine is
 *     service-scoped; the Compose state survives because the composition is
 *     never destroyed).
 *
 * Widths are dp values for a Galaxy Z Fold-class device: cover ~345dp,
 * unfolded main ~673dp.
 */
class LayoutSelectorTest {

    private val defaultMain = LayoutMode.StandardQwerty
    private val defaultSub = LayoutMode.FlickInput12Key

    @Test
    fun `cover width picks the sub-display preference`() {
        assertEquals(
            LayoutMode.FlickInput12Key,
            decideLayout(345f, isSubDisplay = true, defaultMain, defaultSub),
        )
    }

    @Test
    fun `unfolded width picks the main-display preference`() {
        assertEquals(
            LayoutMode.StandardQwerty,
            decideLayout(673f, isSubDisplay = false, defaultMain, defaultSub),
        )
    }

    @Test
    fun `a fold transition switches layout in both directions`() {
        // Fold shut: main -> cover.
        assertEquals(
            LayoutMode.FlickInput12Key,
            decideLayout(345f, isSubDisplay = true, defaultMain, defaultSub),
        )
        // Open up: cover -> main.
        assertEquals(
            LayoutMode.StandardQwerty,
            decideLayout(673f, isSubDisplay = false, defaultMain, defaultSub),
        )
    }

    @Test
    fun `each display honours only its own preference`() {
        val vsplitMain = LayoutMode.FullVSplit
        val compactSub = LayoutMode.CompactQwerty
        // Main preference must not leak onto the cover, and vice versa.
        assertEquals(
            LayoutMode.CompactQwerty,
            decideLayout(345f, isSubDisplay = true, vsplitMain, compactSub),
        )
        assertEquals(
            LayoutMode.FullVSplit,
            decideLayout(673f, isSubDisplay = false, vsplitMain, compactSub),
        )
    }

    @Test
    fun `wide width wins over sub-display state`() {
        // isSubDisplay is width-derived (< 500dp) in FoldableDetector, but the
        // decision tree checks width first — pin that precedence.
        assertEquals(
            LayoutMode.StandardQwerty,
            decideLayout(673f, isSubDisplay = true, defaultMain, defaultSub),
        )
    }

    @Test
    fun `non-foldable phone widths`() {
        assertEquals(
            LayoutMode.StandardQwerty,
            decideLayout(400f, isSubDisplay = false, defaultMain, defaultSub),
        )
        assertEquals(
            LayoutMode.QuickInputPad,
            decideLayout(300f, isSubDisplay = false, defaultMain, defaultSub),
        )
        assertEquals(
            LayoutMode.QuickInputPad,
            decideLayout(150f, isSubDisplay = false, defaultMain, defaultSub),
        )
    }

    @Test
    fun `width boundaries`() {
        assertEquals(
            defaultMain,
            decideLayout(500f, isSubDisplay = false, defaultMain, defaultSub),
        )
        assertEquals(
            LayoutMode.StandardQwerty,
            decideLayout(380f, isSubDisplay = false, defaultMain, defaultSub),
        )
        assertEquals(
            LayoutMode.QuickInputPad,
            decideLayout(379f, isSubDisplay = false, defaultMain, defaultSub),
        )
    }
}
