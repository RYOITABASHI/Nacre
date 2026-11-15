package space.manus.nacre.ime.feedback

import android.content.Context
import space.manus.nacre.config.KeyAction

class FeedbackManager(context: Context) {

    private val haptic = HapticManager(context)
    private val sound = SoundManager(context)

    val hapticManager: HapticManager get() = haptic
    val soundManager: SoundManager get() = sound

    fun onKeyPress(keyAction: KeyAction) {
        haptic.onKeyTap()

        val keyType = when (keyAction) {
            is KeyAction.Space -> SoundManager.KeyType.SPACE
            is KeyAction.Backspace -> SoundManager.KeyType.BACKSPACE
            is KeyAction.Enter -> SoundManager.KeyType.ENTER
            else -> SoundManager.KeyType.NORMAL
        }
        sound.onKeyTap(keyType)
    }

    fun onSwipe() {
        haptic.onKeyTap()
        sound.onKeyTap(SoundManager.KeyType.NORMAL)
    }

    /**
     * One tick of a long-press delete REPEAT (fires every ~50ms while the
     * key is held). Deliberately the light trackball tick, not the full
     * key-press vibration: the strengthened tap would buzz continuously
     * during a long delete. The single deliberate backspace press still
     * goes through [onKeyPress] and gets the full tap.
     */
    fun onDeleteRepeat() {
        haptic.onTrackballStep()
        sound.onKeyTap(SoundManager.KeyType.BACKSPACE)
    }

    fun onLongPress() {
        haptic.onLongPress()
    }

    fun onTrackballStep() {
        haptic.onTrackballStep()
    }

    fun onLayerChange() {
        haptic.onLayerChange()
    }

    fun onError() {
        haptic.onError()
    }

    fun release() {
        haptic.release()
        sound.release()
    }
}
