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
        private const val KEY_SUB_DISPLAY_MODE = "sub_display_mode"
        /** When true, the iOS-style 12-key pad is forced on every display. */
        const val KEY_FORCE_FLICK12 = "force_flick12"
    }

    private val prefs = detector.context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * User-selected preferred layout for the sub-display.
     * When set, overrides the automatic selection for foldable sub-displays.
     * Persisted to SharedPreferences.
     */
    var userSubDisplayMode: LayoutMode = loadSubDisplayMode()
        set(value) {
            field = value
            prefs.edit().putString(KEY_SUB_DISPLAY_MODE, value.name).apply()
        }

    /**
     * Determines the best layout mode for the current screen configuration.
     *
     * Decision tree:
     *  - widthDp >= 500  -> FullVSplit (tablet / unfolded main display)
     *  - foldable sub    -> user preference (default CompactQwerty)
     *  - widthDp >= 380  -> StandardQwerty (normal phone)
     *  - widthDp >= 200  -> QuickInputPad (very small sub-display)
     *  - else            -> QuickInputPad (fallback)
     */
    fun selectLayout(): LayoutMode {
        // Global opt-in to the 12-key pad wins over size-based selection. Read fresh
        // from prefs so a Settings toggle applies without rebuilding this instance.
        if (prefs.getBoolean(KEY_FORCE_FLICK12, false)) return LayoutMode.FlickInput12Key

        val widthDp = detector.getScreenWidthDp()

        return when {
            widthDp >= 500f -> LayoutMode.FullVSplit
            detector.isSubDisplay() -> userSubDisplayMode
            widthDp >= 380f -> LayoutMode.StandardQwerty
            widthDp >= 200f -> LayoutMode.QuickInputPad
            else -> LayoutMode.QuickInputPad
        }
    }

    private fun loadSubDisplayMode(): LayoutMode {
        val name = prefs.getString(KEY_SUB_DISPLAY_MODE, LayoutMode.CompactQwerty.name)
        return try {
            LayoutMode.valueOf(name ?: LayoutMode.CompactQwerty.name)
        } catch (_: IllegalArgumentException) {
            LayoutMode.CompactQwerty
        }
    }
}
