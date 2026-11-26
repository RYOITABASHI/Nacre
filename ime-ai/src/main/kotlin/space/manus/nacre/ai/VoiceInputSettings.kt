package space.manus.nacre.ai

import android.content.Context

/**
 * The user's "音声入力を使う" switch (issue #24). ON by default.
 *
 * OFF hides the dictation mic, refuses to start recording from any entry
 * point, and stops the automatic download of the voice-only models (speech
 * recognition, VAD, and the local refinement LLM) — for devices that have
 * no room for them. Conversion models (compact KenLM, zenz) are unaffected.
 * Models already on the device are left alone.
 *
 * Shared by the settings screen (app), the IME (ime-core) and the model
 * downloader (here), which all run in the app's main process.
 */
object VoiceInputSettings {
    const val PREFS = "nacre_voice"
    const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled).apply()
    }
}
