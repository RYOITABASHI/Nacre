package space.manus.nacre.ime.feedback

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.content.getSystemService

class HapticManager(private val context: Context) {

    enum class Strength(val multiplier: Float) {
        OFF(0f),
        WEAK(0.5f),
        MEDIUM(1.0f),
        STRONG(1.5f),
    }

    companion object {
        private const val PREFS_NAME = "nacre_haptic"
        private const val KEY_STRENGTH = "strength"

        // Key tap: a full-amplitude one-shot tick (scaled by Strength below).
        // The tap used to be amplitude 60/255 for 20ms, and on API 29+ it was
        // routed to EFFECT_CLICK / PRIMITIVE_CLICK, which ignore the amplitude
        // entirely — and the composition path clamps Strength.STRONG to the
        // same 1.0 scale as MEDIUM, so no setting could make taps stronger.
        private const val BASE_AMPLITUDE_TAP = 255
        private const val BASE_AMPLITUDE_LONG_PRESS = 100
        private const val BASE_AMPLITUDE_LAYER = 80
        private const val BASE_AMPLITUDE_ERROR = 180
        private const val BASE_AMPLITUDE_TRACKBALL = 30

        private const val DURATION_TAP_MS = 25L
        private const val DURATION_LONG_PRESS_MS = 50L
        private const val DURATION_LAYER_MS = 30L
        private const val DURATION_ERROR_MS = 100L
        private const val DURATION_TRACKBALL_MS = 10L
    }

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService<VibratorManager>()?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val powerManager: PowerManager? = context.getSystemService()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var strength: Strength = loadStrength()
        set(value) {
            field = value
            prefs.edit().putString(KEY_STRENGTH, value.name).apply()
        }

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_STRENGTH) {
            strength = loadStrength()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun loadStrength(): Strength {
        val name = prefs.getString(KEY_STRENGTH, Strength.MEDIUM.name)
        return try {
            Strength.valueOf(name ?: Strength.MEDIUM.name)
        } catch (_: IllegalArgumentException) {
            Strength.MEDIUM
        }
    }

    private val isAvailable: Boolean
        get() = strength != Strength.OFF &&
                vibrator?.hasVibrator() == true &&
                !isBatterySaverActive() &&
                isSystemHapticEnabled()

    private fun isBatterySaverActive(): Boolean {
        return powerManager?.isPowerSaveMode == true
    }

    /** Respect the system-wide touch-feedback switch (default: on). */
    @Suppress("DEPRECATION") // HAPTIC_FEEDBACK_ENABLED is the documented switch
    private fun isSystemHapticEnabled(): Boolean = try {
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1,
        ) == 1
    } catch (_: Exception) {
        true
    }

    fun onKeyTap() {
        vibrateTap()
    }

    /**
     * The key-press tick: one short one-shot at (strength-scaled) full
     * amplitude, on every API level — deliberately NOT the predefined
     * EFFECT_CLICK / PRIMITIVE_CLICK path used elsewhere, because those
     * ignore amplitude and cap out at a mild, vendor-defined click.
     * Strength ladder: WEAK ≈ 127 amplitude / ~12ms, MEDIUM = 255 / 25ms,
     * STRONG = 255 / ~37ms (amplitude saturates, duration still grows).
     */
    private fun vibrateTap() {
        if (!isAvailable) return
        val vib = vibrator ?: return
        val scaledAmplitude = if (vib.hasAmplitudeControl()) {
            (BASE_AMPLITUDE_TAP * strength.multiplier).toInt().coerceIn(1, 255)
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }
        val durationMs = (DURATION_TAP_MS * strength.multiplier).toLong().coerceIn(10L, 40L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(durationMs, scaledAmplitude))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(durationMs)
        }
    }

    fun onLongPress() {
        vibrate(BASE_AMPLITUDE_LONG_PRESS, DURATION_LONG_PRESS_MS)
    }

    fun onLayerChange() {
        vibrate(BASE_AMPLITUDE_LAYER, DURATION_LAYER_MS)
    }

    fun onError() {
        vibrate(BASE_AMPLITUDE_ERROR, DURATION_ERROR_MS)
    }

    fun onTrackballStep() {
        vibrate(BASE_AMPLITUDE_TRACKBALL, DURATION_TRACKBALL_MS)
    }

    private fun vibrate(baseAmplitude: Int, durationMs: Long) {
        if (!isAvailable) return
        val vib = vibrator ?: return

        // SPEC: hasAmplitudeControl() check — use DEFAULT_AMPLITUDE if not supported
        val hasAmplitude = vib.hasAmplitudeControl()
        val scaledAmplitude = if (hasAmplitude) {
            (baseAmplitude * strength.multiplier).toInt().coerceIn(1, 255)
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // API 33+: Use composition primitives for precise haptics
            try {
                val composition = VibrationEffect.startComposition()
                    .addPrimitive(
                        VibrationEffect.Composition.PRIMITIVE_CLICK,
                        strength.multiplier.coerceIn(0f, 1f),
                    )
                    .compose()
                vib.vibrate(composition)
            } catch (_: Exception) {
                // Fall back to predefined effect if composition fails
                vibratePredefined(vib, scaledAmplitude, durationMs)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+: Predefined effects
            vibratePredefined(vib, scaledAmplitude, durationMs)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // API 26-28: Custom one-shot
            val effect = VibrationEffect.createOneShot(durationMs, scaledAmplitude)
            vib.vibrate(effect)
        } else {
            // API < 26: Legacy vibration
            @Suppress("DEPRECATION")
            vib.vibrate(durationMs)
        }
    }

    private fun vibratePredefined(vib: Vibrator, amplitude: Int, durationMs: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val effect = VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                vib.vibrate(effect)
            } catch (_: Exception) {
                // Fall back to one-shot
                val effect = VibrationEffect.createOneShot(durationMs, amplitude)
                vib.vibrate(effect)
            }
        }
    }
}
