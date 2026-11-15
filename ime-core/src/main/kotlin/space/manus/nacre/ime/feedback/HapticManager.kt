package space.manus.nacre.ime.feedback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.content.ContextCompat
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

    // --- Cached availability inputs --------------------------------------
    // onKeyTap() runs on the main thread for every key press, and the
    // availability checks it used to perform inline were all IPC:
    // Vibrator.hasVibrator() / hasAmplitudeControl() (binder calls into
    // the vibrator service), PowerManager.isPowerSaveMode (binder), and a
    // Settings.System read through the ContentResolver — several
    // round-trips stacked in front of the vibrate() call itself on every
    // single tap. The hardware facts never change for the life of the
    // process, so they are resolved once; the two system switches are
    // kept current by listeners instead of being re-read per tap.
    private val hasVibratorCached: Boolean by lazy { vibrator?.hasVibrator() == true }
    private val hasAmplitudeControlCached: Boolean by lazy {
        vibrator?.hasAmplitudeControl() == true
    }

    @Volatile
    private var powerSaveActive: Boolean = powerManager?.isPowerSaveMode == true

    @Volatile
    private var systemHapticEnabled: Boolean = isSystemHapticEnabled()

    private val powerSaveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            powerSaveActive = powerManager?.isPowerSaveMode == true
        }
    }

    private val hapticSettingObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                systemHapticEnabled = isSystemHapticEnabled()
            }
        }

    private var powerSaveReceiverRegistered = false
    private var hapticObserverRegistered = false

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        registerSystemListeners()
        // Re-read both flags AFTER registering: a system change that landed
        // between the field initializers above and listener registration
        // would otherwise be missed until the NEXT change. From here on,
        // changes arrive via the listeners, and both writers store the
        // live system value, so ordering between them cannot go stale.
        powerSaveActive = powerManager?.isPowerSaveMode == true
        systemHapticEnabled = isSystemHapticEnabled()
    }

    private fun registerSystemListeners() {
        // Each registration is tracked independently so release() can undo
        // exactly the ones that succeeded. Registration failure leaves the
        // flags at their init-time refresh values (see init) — feedback
        // behavior stays correct at startup, just not live-updated.
        try {
            // ACTION_POWER_SAVE_MODE_CHANGED is a system broadcast, so a
            // NOT_EXPORTED receiver still receives it (the flag only gates
            // other apps as senders).
            ContextCompat.registerReceiver(
                context,
                powerSaveReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            powerSaveReceiverRegistered = true
        } catch (_: Exception) {
        }
        try {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_ENABLED),
                false,
                hapticSettingObserver,
            )
            hapticObserverRegistered = true
        } catch (_: Exception) {
        }
    }

    /** Unregister the system listeners (called from FeedbackManager.release()). */
    fun release() {
        if (powerSaveReceiverRegistered) {
            powerSaveReceiverRegistered = false
            try {
                context.unregisterReceiver(powerSaveReceiver)
            } catch (_: Exception) {
            }
        }
        if (hapticObserverRegistered) {
            hapticObserverRegistered = false
            try {
                context.contentResolver.unregisterContentObserver(hapticSettingObserver)
            } catch (_: Exception) {
            }
        }
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
                hasVibratorCached &&
                !powerSaveActive &&
                systemHapticEnabled

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
        val scaledAmplitude = if (hasAmplitudeControlCached) {
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
        val scaledAmplitude = if (hasAmplitudeControlCached) {
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
