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

        /**
         * The pure layout decision (no Android dependencies), split out so the
         * fold behaviour is JVM-testable. [selectLayout] only gathers the
         * inputs — current window width, sub-display state, stored
         * preferences — and delegates here.
         *
         * Decision tree:
         *  - widthDp >= 500  -> main-display preference (default StandardQwerty; V-Split opt-in)
         *  - foldable sub    -> sub-display preference (default FlickInput12Key)
         *  - widthDp >= 380  -> StandardQwerty (normal phone)
         *  - else            -> QuickInputPad (very small sub-display / fallback)
         */
        fun decideLayout(
            widthDp: Float,
            isSubDisplay: Boolean,
            mainDisplayMode: LayoutMode,
            subDisplayMode: LayoutMode,
        ): LayoutMode = when {
            widthDp >= 500f -> mainDisplayMode
            isSubDisplay -> subDisplayMode
            widthDp >= 380f -> LayoutMode.StandardQwerty
            else -> LayoutMode.QuickInputPad
        }
    }

    private val prefs = detector.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Determines the best layout mode for the current screen configuration.
     * Preferences are read fresh on every call so a Settings change applies
     * on the next pick; the cover and main displays have separate preferences
     * and never affect each other.
     */
    fun selectLayout(): LayoutMode {
        val widthDp = detector.getScreenWidthDp()
        return decideLayout(
            widthDp = widthDp,
            isSubDisplay = detector.isSubDisplay(),
            mainDisplayMode = loadMainDisplayMode(),
            subDisplayMode = loadSubDisplayMode(),
        )
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
