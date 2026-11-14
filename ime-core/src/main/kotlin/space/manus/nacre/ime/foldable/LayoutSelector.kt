package space.manus.nacre.ime.foldable

import android.content.Context

/**
 * Available keyboard layout modes for different screen sizes and form factors.
 */
enum class LayoutMode {
    /** V-split keyboard for tablets and large main displays (>= 500dp). */
    FullVSplit,

    /** Standard QWERTY layout for normal phone screens (>= 380dp). */
    StandardQwerty,

    /** Compact Japanese-base QWERTY for smaller screens or foldable sub-displays. */
    CompactQwerty,

    /** Minimal macro pad for very small sub-displays (e.g. Z Flip cover). */
    QuickInputPad,

    /** 12-key flick input for Japanese on foldable sub-displays. */
    FlickInput12Key,
}

/**
 * Selects the appropriate keyboard layout based on screen dimensions
 * and foldable device state.
 */
class LayoutSelector(private val detector: FoldableDetector) {

    companion object {
        private const val PREFS_NAME = "nacre_layout"
        /** Layout preference for the foldable cover / sub-display only. */
        const val KEY_SUB_DISPLAY_MODE = "sub_display_mode"
        /** Layout preference for the main / wide display (>= 500dp) only. */
        const val KEY_MAIN_DISPLAY_MODE = "main_display_mode"
    }

    private val prefs = detector.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Determines the best layout mode for the current screen configuration.
     *
     * Decision tree:
     *  - widthDp >= 500  -> user preference (default StandardQwerty; V-Split opt-in)
     *  - foldable sub    -> user preference (default FlickInput12Key)
     *  - widthDp >= 380  -> StandardQwerty (normal phone)
     *  - widthDp >= 200  -> QuickInputPad (very small sub-display)
     *  - else            -> QuickInputPad (fallback)
     */
    fun selectLayout(): LayoutMode {
        val widthDp = detector.getScreenWidthDp()

        return when {
            // Main / unfolded display: user preference, read fresh so a Settings
            // change applies on the next keyboard open. The cover display is
            // unaffected (it has its own preference below).
            widthDp >= 500f -> loadMainDisplayMode()
            // Foldable cover / sub-display: user preference, read fresh so a Settings
            // toggle applies on the next keyboard open. The main display is unaffected.
            detector.isSubDisplay() -> loadSubDisplayMode()
            widthDp >= 380f -> LayoutMode.StandardQwerty
            widthDp >= 200f -> LayoutMode.QuickInputPad
            else -> LayoutMode.QuickInputPad
        }
    }

    private fun loadMainDisplayMode(): LayoutMode {
        // Default is Standard QWERTY: the main display is for plain romaji
        // QWERTY typing. V-Split (rotated halves + centre trackball) stays one
        // Settings toggle away. Only the two wide-display modes are accepted;
        // anything else stored falls back to the default.
        val name = prefs.getString(KEY_MAIN_DISPLAY_MODE, LayoutMode.StandardQwerty.name)
        return when (name) {
            LayoutMode.FullVSplit.name -> LayoutMode.FullVSplit
            else -> LayoutMode.StandardQwerty
        }
    }

    private fun loadSubDisplayMode(): LayoutMode {
        // Default is the 12-key pad: the cover display is where quick Japanese
        // replies happen, and the 12-key (flick / tap-cycle) pad is the layout
        // users expect there. CompactQwerty stays one toggle away in Settings.
        val name = prefs.getString(KEY_SUB_DISPLAY_MODE, LayoutMode.FlickInput12Key.name)
        return try {
            LayoutMode.valueOf(name ?: LayoutMode.FlickInput12Key.name)
        } catch (_: IllegalArgumentException) {
            LayoutMode.FlickInput12Key
        }
    }
}
