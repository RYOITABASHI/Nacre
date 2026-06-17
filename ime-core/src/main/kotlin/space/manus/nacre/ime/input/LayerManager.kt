package space.manus.nacre.ime.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import space.manus.nacre.config.DefaultLayouts
import space.manus.nacre.config.KeyboardLayout
import space.manus.nacre.config.PresetProvider

enum class Layer {
    Base,
    Fn1,
    Fn2,
}

class LayerManager {
    var currentLayer by mutableStateOf(Layer.Base)
        private set

    var isShifted by mutableStateOf(false)
        private set

    var isJapanese by mutableStateOf(true)
        private set

    var isAltActive by mutableStateOf(false)
        private set

    var isCommandPaletteRequested by mutableStateOf(false)
    var isEmojiRequested by mutableStateOf(false)
    var isSymbolsRequested by mutableStateOf(false)

    var activePreset: PresetProvider.PresetType = PresetProvider.PresetType.Default

    // Fn hold (temporary layer switch) state
    private var fnHeld = false

    fun toggleFn() {
        currentLayer = when (currentLayer) {
            Layer.Base -> Layer.Fn1
            Layer.Fn1 -> Layer.Base
            Layer.Fn2 -> Layer.Base
        }
    }

    /** Called when Fn key is pressed down (hold = temporary switch) */
    fun fnDown() {
        when (currentLayer) {
            Layer.Base -> {
                fnHeld = true
                currentLayer = Layer.Fn1
            }
            Layer.Fn1, Layer.Fn2 -> {
                // Already on Fn layer — mark as held so fnUp can toggle back
                fnHeld = true
            }
        }
    }

    /** Called when Fn key is released. If held, return to base. */
    fun fnUp(wasTap: Boolean) {
        if (fnHeld) {
            val wasLayer = currentLayer
            fnHeld = false
            if (wasTap) {
                // Short tap: toggle
                when (wasLayer) {
                    Layer.Base -> currentLayer = Layer.Fn1
                    Layer.Fn1 -> currentLayer = Layer.Base
                    Layer.Fn2 -> currentLayer = Layer.Base  // Fn2→Base on Fn tap
                }
            } else {
                // Was held: return to base
                currentLayer = Layer.Base
            }
        }
    }

    fun toggleFn2() {
        currentLayer = when (currentLayer) {
            Layer.Fn1 -> Layer.Fn2
            Layer.Fn2 -> Layer.Fn1
            Layer.Base -> Layer.Fn2
        }
    }

    fun resetToBase() {
        currentLayer = Layer.Base
    }

    fun toggleShift() {
        isShifted = !isShifted
    }

    fun toggleJapanese() {
        isJapanese = !isJapanese
    }

    fun requestCommandPalette() {
        isCommandPaletteRequested = true
    }

    fun toggleAlt() {
        isAltActive = !isAltActive
    }

    fun consumeAlt(): Boolean {
        if (isAltActive) {
            isAltActive = false
            return true
        }
        return false
    }

    fun currentLayout(compactBase: Boolean = false): KeyboardLayout = when (currentLayer) {
        Layer.Base -> if (compactBase) {
            DefaultLayouts.compactJapaneseQwerty
        } else {
            PresetProvider.getLayout(activePreset)
        }
        Layer.Fn1 -> DefaultLayouts.fnLayer1
        Layer.Fn2 -> DefaultLayouts.fnLayer2
    }
}
