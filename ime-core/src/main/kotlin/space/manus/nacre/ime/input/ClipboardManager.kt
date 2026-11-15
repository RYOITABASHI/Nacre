package space.manus.nacre.ime.input

import android.content.ClipDescription
import android.content.ClipboardManager as SystemClipboardManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Android wrapper around [ClipboardHistoryStore]: listens for system
 * clipboard changes while the IME is alive, persists the history to
 * app-private SharedPreferences, and mirrors it into [history] for Compose.
 *
 * Privacy: clips flagged sensitive (the EXTRA_IS_SENSITIVE clip extra —
 * password managers set this) and clips copied while a password field is
 * focused are never recorded. A clip
 * rejected for either reason is also kept out of the delayed-import path
 * ([captureCurrentClip]) for the rest of this process's lifetime, so
 * opening the panel later from a normal field cannot resurrect it.
 * Contents are never logged and never leave the device.
 */
class ClipboardManager(
    private val context: Context,
    private val isPasswordField: () -> Boolean = { false },
) {

    companion object {
        private const val PREFS_NAME = "nacre_clipboard"
        private const val KEY_HISTORY = "history"

        /** Value of ClipDescription.EXTRA_IS_SENSITIVE (the constant is API 33+). */
        private const val EXTRA_IS_SENSITIVE_KEY = "android.content.extra.IS_SENSITIVE"
    }

    private val store = ClipboardHistoryStore()

    val history = mutableStateListOf<ClipboardEntry>()

    /** Text of the current system clip as last observed (null if unknown). */
    var currentClipText by mutableStateOf<String?>(null)
        private set

    /**
     * Texts rejected by the privacy rules in this process. Memory-only —
     * deliberately never persisted (storing password-derived data, even
     * for comparison, would defeat the purpose).
     */
    private val rejectedClipTexts = mutableSetOf<String>()

    private val systemClipboard =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as SystemClipboardManager

    private val clipChangedListener =
        SystemClipboardManager.OnPrimaryClipChangedListener {
            val clip = systemClipboard.primaryClip
            val text = if (clip == null || clip.itemCount == 0) {
                null
            } else {
                clip.getItemAt(0).coerceToText(context)?.toString()
            }
            // Always publish the observation (including null after the
            // clipboard is cleared) so the panel's "Current" marker can
            // never go stale.
            currentClipText = text
            if (clip == null || text == null) return@OnPrimaryClipChangedListener
            capture(text, isSensitive = isSensitiveClip(clip.description))
        }

    fun startListening() {
        loadHistory(context)
        systemClipboard.addPrimaryClipChangedListener(clipChangedListener)
    }

    fun stopListening() {
        systemClipboard.removePrimaryClipChangedListener(clipChangedListener)
        saveHistory(context)
    }

    /** True when the clip's description carries the sensitive flag. */
    private fun isSensitiveClip(description: ClipDescription?): Boolean {
        if (description == null) return false
        // ClipDescription.getExtras() exists since API 24; only the named
        // constant (ClipDescription.EXTRA_IS_SENSITIVE) arrived in API 33,
        // so read the extra by its literal key on every supported version.
        return try {
            description.extras?.getBoolean(EXTRA_IS_SENSITIVE_KEY, false) ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * The single funnel for live clip observations. Records the clip when
     * the privacy rules allow it; a rejected text is remembered so the
     * delayed-import path cannot bring it back later in this process.
     */
    fun capture(text: String?, isSensitive: Boolean) {
        if (!ClipboardHistoryStore.shouldCapture(text, isSensitive, isPasswordField())) {
            if (!text.isNullOrBlank()) rejectedClipTexts.add(text)
            return
        }
        store.add(text!!, System.currentTimeMillis())
        syncAndSave()
    }

    fun addEntry(text: String, isPassword: Boolean = false) {
        if (isPassword) return
        capture(text, isSensitive = false)
    }

    /**
     * Import the clip already on the system clipboard, if any. Covers
     * copies whose change event this process missed or could not read
     * (some devices only let the IME read the clip while its view is
     * shown). Called when the clipboard panel opens — deliberately NOT at
     * startup, where the clip's provenance is least certain. The same
     * privacy rules as live capture apply, plus the rejected-text guard.
     */
    fun captureCurrentClip() {
        try {
            val clip = systemClipboard.primaryClip
            if (clip == null || clip.itemCount == 0) {
                currentClipText = null
                return
            }
            val text = clip.getItemAt(0).coerceToText(context)?.toString()
            currentClipText = text
            if (text == null) return
            if (text in rejectedClipTexts) return
            capture(text, isSensitive = isSensitiveClip(clip.description))
        } catch (_: Exception) {
            // Clipboard read can be denied while the IME is not focused.
        }
    }

    /** Toggle pin on the entry at [index]; pinned entries survive the size cap. */
    fun togglePin(index: Int) {
        if (store.togglePin(index)) syncAndSave()
    }

    fun getEntry(index: Int): ClipboardEntry? {
        return store.entries.getOrNull(index)
    }

    fun removeEntry(index: Int) {
        if (store.removeAt(index)) syncAndSave()
    }

    fun clearHistory() {
        store.clear()
        history.clear()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun syncAndSave() {
        history.clear()
        history.addAll(store.entries)
        saveHistory(context)
    }

    fun saveHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val array = JSONArray()
        for (entry in store.entries) {
            if (entry.isPassword) continue
            val obj = JSONObject().apply {
                put("text", entry.text)
                put("timestamp", entry.timestamp)
                put("pinned", entry.pinned)
                put("truncated", entry.truncated)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun loadHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_HISTORY, null) ?: return
        try {
            val array = JSONArray(json)
            val loaded = mutableListOf<ClipboardEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                loaded.add(
                    ClipboardEntry(
                        text = obj.getString("text"),
                        timestamp = obj.getLong("timestamp"),
                        pinned = obj.optBoolean("pinned", false),
                        truncated = obj.optBoolean("truncated", false),
                    )
                )
            }
            store.replaceAll(loaded)
            history.clear()
            history.addAll(store.entries)
        } catch (_: Exception) {
            // Corrupted prefs — ignore
        }
    }
}
