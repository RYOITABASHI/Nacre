package space.manus.nacre.config

import android.content.Context
import android.content.SharedPreferences

class ConfigRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "nacre_config"

        private const val KEY_PRESET = "selected_preset"
        private const val KEY_THEME = "selected_theme"
        private const val KEY_HAPTIC_STRENGTH = "haptic_strength"
        private const val KEY_SOUND_PROFILE = "sound_profile"
        private const val KEY_SOUND_VOLUME = "sound_volume"
        private const val KEY_KEYBOARD_HEIGHT = "keyboard_height"
        private const val KEY_V_ANGLE = "v_angle"
        private const val KEY_AUTO_CONVERT_ENABLED = "auto_convert_enabled"
        private const val KEY_LIGHTING_MODE = "lighting_mode"

        // Defaults
        private const val DEFAULT_PRESET = "Default"
        private const val DEFAULT_THEME = "AMOLED"
        private const val DEFAULT_HAPTIC_STRENGTH = 50
        private const val DEFAULT_SOUND_PROFILE = "soft"
        private const val DEFAULT_SOUND_VOLUME = 50
        private const val DEFAULT_KEYBOARD_HEIGHT = 280
        // 4f matches the angle the V-Split renderer historically hardcoded, so
        // wiring this setting through does not change the unset default look.
        private const val DEFAULT_V_ANGLE = 4f
        private const val DEFAULT_AUTO_CONVERT = true
        private const val DEFAULT_LIGHTING_MODE = "none"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- Preset ----

    var selectedPreset: String
        get() = prefs.getString(KEY_PRESET, DEFAULT_PRESET) ?: DEFAULT_PRESET
        set(value) = prefs.edit().putString(KEY_PRESET, value).apply()

    // ---- Theme ----

    var selectedTheme: String
        get() = prefs.getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME
        set(value) = prefs.edit().putString(KEY_THEME, value).apply()

    // ---- Haptic ----

    var hapticStrength: Int
        get() = prefs.getInt(KEY_HAPTIC_STRENGTH, DEFAULT_HAPTIC_STRENGTH)
        set(value) = prefs.edit().putInt(KEY_HAPTIC_STRENGTH, value.coerceIn(0, 100)).apply()

    // ---- Sound ----

    var soundProfile: String
        get() = prefs.getString(KEY_SOUND_PROFILE, DEFAULT_SOUND_PROFILE) ?: DEFAULT_SOUND_PROFILE
        set(value) = prefs.edit().putString(KEY_SOUND_PROFILE, value).apply()

    var soundVolume: Int
        get() = prefs.getInt(KEY_SOUND_VOLUME, DEFAULT_SOUND_VOLUME)
        set(value) = prefs.edit().putInt(KEY_SOUND_VOLUME, value.coerceIn(0, 100)).apply()

    // ---- Layout ----

    var keyboardHeight: Int
        get() = prefs.getInt(KEY_KEYBOARD_HEIGHT, DEFAULT_KEYBOARD_HEIGHT)
        set(value) = prefs.edit().putInt(KEY_KEYBOARD_HEIGHT, value.coerceIn(180, 400)).apply()

    var vAngle: Float
        get() = prefs.getFloat(KEY_V_ANGLE, DEFAULT_V_ANGLE)
        set(value) = prefs.edit().putFloat(KEY_V_ANGLE, value.coerceIn(0f, 30f)).apply()

    // ---- Auto-convert ----

    var autoConvertEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONVERT_ENABLED, DEFAULT_AUTO_CONVERT)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CONVERT_ENABLED, value).apply()

    // ---- Lighting ----

    var lightingMode: String
        get() = prefs.getString(KEY_LIGHTING_MODE, DEFAULT_LIGHTING_MODE) ?: DEFAULT_LIGHTING_MODE
        set(value) = prefs.edit().putString(KEY_LIGHTING_MODE, value).apply()

    // ---- Reset ----

    fun resetToDefaults() {
        prefs.edit()
            .putString(KEY_PRESET, DEFAULT_PRESET)
            .putString(KEY_THEME, DEFAULT_THEME)
            .putInt(KEY_HAPTIC_STRENGTH, DEFAULT_HAPTIC_STRENGTH)
            .putString(KEY_SOUND_PROFILE, DEFAULT_SOUND_PROFILE)
            .putInt(KEY_SOUND_VOLUME, DEFAULT_SOUND_VOLUME)
            .putInt(KEY_KEYBOARD_HEIGHT, DEFAULT_KEYBOARD_HEIGHT)
            .putFloat(KEY_V_ANGLE, DEFAULT_V_ANGLE)
            .putBoolean(KEY_AUTO_CONVERT_ENABLED, DEFAULT_AUTO_CONVERT)
            .putString(KEY_LIGHTING_MODE, DEFAULT_LIGHTING_MODE)
            .apply()
    }
}
