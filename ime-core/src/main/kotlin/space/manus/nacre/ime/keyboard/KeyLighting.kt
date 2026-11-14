package space.manus.nacre.ime.keyboard

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import kotlin.math.*

/**
 * Mechanical keyboard RGB lighting engine.
 * Provides per-key color computation for various lighting modes.
 */
class KeyLighting(context: Context) {

    enum class Mode(val displayName: String) {
        OFF("Off"),
        STATIC("Static"),
        REACTIVE("Reactive"),
        WAVE("Wave"),
        BREATHING("Breathing"),
        HEATMAP("Heatmap"),
        MATRIX("Matrix"),
    }

    companion object {
        private const val PREFS_NAME = "nacre_lighting"
        private const val KEY_MODE = "mode"
        private const val KEY_HUE = "hue"

        // Reactive wave speed
        private const val REACTIVE_DURATION_MS = 400L
        private const val REACTIVE_WAVE_SPEED = 0.003f // keys per ms

        // Wave animation period
        private const val WAVE_PERIOD_MS = 3000L

        // Breathing animation period
        private const val BREATHING_PERIOD_MS = 4000L
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mode: Mode = loadMode()
        private set

    /** Base hue (0-360) for static/wave/breathing modes */
    var baseHue: Float = loadHue()
        private set

    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            KEY_MODE -> mode = loadMode()
            KEY_HUE -> baseHue = loadHue()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    // Reactive: recently pressed keys with timestamps
    private val reactivePresses = mutableStateMapOf<String, Long>()

    // Heatmap: key press counts
    private val heatmapCounts = mutableStateMapOf<String, Int>()
    private var heatmapMax = 1

    // Matrix: random column activations
    private val matrixColumns = mutableStateMapOf<Int, Long>()

    // Animation ticker — updated by KeyboardScreen, at 20fps and ONLY while a
    // time-animated mode is active and the IME window is resumed.
    var animationTick by mutableStateOf(0L)

    /**
     * True for modes whose colors actually change with time (they read
     * [animationTick]). OFF / STATIC / HEATMAP colors are time-invariant:
     * STATIC depends only on [baseHue], HEATMAP only on press counts. Driving
     * the ticker for them — as KeyboardScreen used to do for every non-OFF
     * mode — recomposed every key 20x/sec while changing nothing on screen.
     */
    val isTimeAnimated: Boolean
        get() = when (mode) {
            Mode.REACTIVE, Mode.WAVE, Mode.BREATHING, Mode.MATRIX -> true
            Mode.OFF, Mode.STATIC, Mode.HEATMAP -> false
        }

    fun setMode(newMode: Mode) {
        mode = newMode
        prefs.edit().putString(KEY_MODE, newMode.name).apply()
    }

    fun setBaseHue(hue: Float) {
        baseHue = hue.coerceIn(0f, 360f)
        prefs.edit().putFloat(KEY_HUE, baseHue).apply()
    }

    /** Call when a key is pressed */
    fun onKeyPress(keyId: String, column: Int = 0) {
        val now = System.currentTimeMillis()
        reactivePresses[keyId] = now

        // Heatmap tracking
        heatmapCounts[keyId] = (heatmapCounts[keyId] ?: 0) + 1
        heatmapMax = maxOf(heatmapMax, heatmapCounts[keyId] ?: 1)

        // Matrix: activate column
        matrixColumns[column] = now
    }

    // WASD highlight: fluorescent orange always-on
    private val wasdKeys = setOf("w", "a", "s", "d")
    private val FLUORESCENT_ORANGE = Color(0xFFFF6600)

    /**
     * Get the border/glow color for a specific key at the current time.
     * Returns Color.Transparent if no lighting should be shown.
     */
    fun getKeyColor(keyId: String, row: Int, column: Int): Color {
        // WASD always glows fluorescent orange
        if (keyId in wasdKeys) {
            return FLUORESCENT_ORANGE.copy(alpha = 0.85f)
        }

        // animationTick is read ONLY by the time-animated branches. Reading it
        // unconditionally (as this function used to) subscribed every calling
        // composable to the 20fps ticker even in OFF/STATIC/HEATMAP modes,
        // where the returned color cannot depend on it.
        return when (mode) {
            Mode.OFF -> Color.Transparent
            Mode.STATIC -> hsvToColor(baseHue, 0.8f, 0.7f)
            Mode.REACTIVE -> reactiveColor(keyId, animationTick)
            Mode.WAVE -> waveColor(column, animationTick)
            Mode.BREATHING -> breathingColor(animationTick)
            Mode.HEATMAP -> heatmapColor(keyId)
            Mode.MATRIX -> matrixColor(row, column, animationTick)
        }
    }

    private fun reactiveColor(keyId: String, now: Long): Color {
        val pressTime = reactivePresses[keyId] ?: return Color.Transparent
        val elapsed = now - pressTime
        if (elapsed > REACTIVE_DURATION_MS) {
            reactivePresses.remove(keyId)
            return Color.Transparent
        }
        val progress = elapsed.toFloat() / REACTIVE_DURATION_MS
        val alpha = 1f - progress // Fade out
        val hue = baseHue + progress * 30f // Slight hue shift during fade
        return hsvToColor(hue % 360f, 0.9f, 0.9f).copy(alpha = alpha * 0.8f)
    }

    private fun waveColor(column: Int, now: Long): Color {
        val phase = (now % WAVE_PERIOD_MS).toFloat() / WAVE_PERIOD_MS
        val columnPhase = column.toFloat() / 10f
        val wave = sin(2.0 * PI * (phase - columnPhase)).toFloat()
        val brightness = (wave + 1f) / 2f * 0.6f + 0.1f
        val hue = (baseHue + column * 20f) % 360f
        return hsvToColor(hue, 0.8f, brightness).copy(alpha = brightness)
    }

    private fun breathingColor(now: Long): Color {
        val phase = (now % BREATHING_PERIOD_MS).toFloat() / BREATHING_PERIOD_MS
        val breath = (sin(2.0 * PI * phase.toDouble()).toFloat() + 1f) / 2f
        val alpha = breath * 0.5f + 0.05f
        return hsvToColor(baseHue, 0.7f, 0.8f).copy(alpha = alpha)
    }

    private fun heatmapColor(keyId: String): Color {
        val count = heatmapCounts[keyId] ?: 0
        if (count == 0) return Color(0xFF1A1AFF).copy(alpha = 0.2f) // Cold blue

        val ratio = count.toFloat() / heatmapMax
        // Blue (cold) → Cyan → Green → Yellow → Red (hot)
        val hue = (1f - ratio) * 240f // 240=blue, 0=red
        return hsvToColor(hue, 0.9f, 0.8f).copy(alpha = 0.3f + ratio * 0.5f)
    }

    private fun matrixColor(row: Int, column: Int, now: Long): Color {
        val activationTime = matrixColumns[column] ?: return Color.Transparent
        val elapsed = now - activationTime
        // Matrix drip: each row lights up with a delay
        val rowDelay = row * 150L
        val rowElapsed = elapsed - rowDelay
        if (rowElapsed < 0 || rowElapsed > 600L) return Color.Transparent

        val alpha = (1f - rowElapsed.toFloat() / 600f) * 0.7f
        return Color(0xFF00FF41).copy(alpha = alpha) // Matrix green
    }

    private fun hsvToColor(h: Float, s: Float, v: Float): Color {
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(r + m, g + m, b + m, 1f)
    }

    private fun loadMode(): Mode {
        val name = prefs.getString(KEY_MODE, Mode.OFF.name)
        return try {
            Mode.valueOf(name ?: Mode.OFF.name)
        } catch (_: IllegalArgumentException) {
            Mode.OFF
        }
    }

    private fun loadHue(): Float {
        return prefs.getFloat(KEY_HUE, 170f) // Default: cyan-ish (#00D4AA ≈ 163°)
    }
}

/**
 * The lighting border of a single key, isolated in its own tiny recomposition
 * scope.
 *
 * Key composables used to call [KeyLighting.getKeyColor] in their own body —
 * and the flick keys additionally read `animationTick` directly — so every
 * 20fps tick invalidated and recomposed EVERY key on the pad (gesture
 * handlers, popups, labels and all). Here the color is computed inside a
 * [derivedStateOf] read only by this border: a tick recomputes colors off to
 * the side, and this composable recomposes only when THIS key's color
 * actually changes. In non-animated modes nothing subscribes to the tick at
 * all (see [KeyLighting.getKeyColor]).
 *
 * [suppressed] hides the border without reading the color at all — used while
 * the key is pressed, where the press glow takes precedence, exactly as the
 * old in-body border selection did.
 */
@Composable
fun KeyLightingBorder(
    lighting: KeyLighting,
    keyId: String,
    row: Int,
    column: Int,
    shape: Shape,
    suppressed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (suppressed) return
    val colorState = remember(lighting, keyId, row, column, lighting.mode, lighting.baseHue) {
        derivedStateOf { lighting.getKeyColor(keyId, row, column) }
    }
    val color by colorState
    if (color != Color.Transparent) {
        Box(modifier.border(width = 1.dp, color = color, shape = shape))
    }
}
