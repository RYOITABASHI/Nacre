package space.manus.nacre.ai.cloud

import android.content.Context

/**
 * Opt-in cloud ASR (speech-to-text) settings. When an API key is present, the
 * utterance audio is sent on stop to an OpenAI-compatible /audio/transcriptions
 * endpoint (default: Groq whisper-large-v3-turbo) for a high-accuracy final
 * result, replacing the on-device transcription. Audio leaves the device only
 * when a key is configured — this is the privacy trade-off the user opts into.
 */
object CloudAsrConfig {
    private const val PREFS = "nacre_cloud_asr"
    private const val KEY_API = "api_key"
    private const val KEY_BASE = "base_url"
    private const val KEY_MODEL = "model"

    const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"
    const val DEFAULT_MODEL = "whisper-large-v3-turbo"

    // MODE_MULTI_PROCESS: the key is written by the Settings UI (main process)
    // but read by WhisperService (:whisper process). MODE_PRIVATE caches per
    // process, so :whisper never saw the key → cloud ASR silently stayed off.
    // This flag re-reads the file from disk on each open, which is correct for
    // our access pattern (rare writes in main, reads at recognition start).
    @Suppress("DEPRECATION")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)

    fun apiKey(ctx: Context): String? = prefs(ctx).getString(KEY_API, null)

    fun setApiKey(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_API, value.trim().ifEmpty { null }).apply()
    }

    fun baseUrl(ctx: Context): String =
        prefs(ctx).getString(KEY_BASE, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL

    fun setBaseUrl(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_BASE, value.trim().ifEmpty { null }).apply()
    }

    fun model(ctx: Context): String =
        prefs(ctx).getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    fun setModel(ctx: Context, value: String) {
        prefs(ctx).edit().putString(KEY_MODEL, value.trim().ifEmpty { null }).apply()
    }

    /** Cloud ASR runs only when a key has been configured. */
    fun isEnabled(ctx: Context): Boolean = !apiKey(ctx).isNullOrBlank()
}
