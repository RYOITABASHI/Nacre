package space.manus.nacre.ime.input

data class ClipboardEntry(
    val text: String,
    val timestamp: Long,
    val isPassword: Boolean = false,
    /** Pinned entries are never evicted by the size cap and are shown first. */
    val pinned: Boolean = false,
    /** True when the original clip exceeded the per-entry length cap. */
    val truncated: Boolean = false,
)

/**
 * Pure clipboard-history list logic (no Android dependencies, JVM-testable).
 *
 * Owns the capture decision and the entry list: dedupe with move-to-top,
 * per-entry length cap, size cap with pinned entries exempt, pin toggling,
 * removal and clear. [ClipboardManager] is the thin Android wrapper that
 * feeds it system clip events and persists the result.
 *
 * Privacy rules enforced here:
 * - Clips flagged sensitive (Android's EXTRA_IS_SENSITIVE, set by password
 *   managers) are never recorded.
 * - Clips copied while a password field is focused are never recorded.
 * - Blank text is never recorded.
 */
class ClipboardHistoryStore {

    companion object {
        /** Max unpinned entries kept; pinned entries are exempt. */
        const val MAX_ENTRIES = 30

        /** Per-entry character cap; longer clips are stored truncated. */
        const val MAX_ENTRY_LENGTH = 2000

        /** Whether a clip with these properties may enter the history. */
        fun shouldCapture(text: String?, isSensitive: Boolean, isPasswordField: Boolean): Boolean {
            if (text.isNullOrBlank()) return false
            if (isSensitive) return false
            if (isPasswordField) return false
            return true
        }

        /** The stored form of [text]: capped at [MAX_ENTRY_LENGTH]. */
        fun truncate(text: String): Pair<String, Boolean> =
            if (text.length > MAX_ENTRY_LENGTH) {
                text.substring(0, MAX_ENTRY_LENGTH) to true
            } else {
                text to false
            }
    }

    private val _entries = mutableListOf<ClipboardEntry>()

    /** Newest first. Defensive copy — mutate via the methods below. */
    val entries: List<ClipboardEntry>
        get() = _entries.toList()

    /**
     * Record [text] as the newest entry. An identical existing entry is
     * moved to the top (its pin state survives, its timestamp refreshes).
     * Returns true if the list changed.
     */
    fun add(text: String, timestamp: Long): Boolean {
        val (stored, truncated) = truncate(text)
        val wasPinned = _entries.firstOrNull { it.text == stored }?.pinned ?: false
        _entries.removeAll { it.text == stored }
        _entries.add(
            0,
            ClipboardEntry(
                text = stored,
                timestamp = timestamp,
                pinned = wasPinned,
                truncated = truncated,
            ),
        )
        trimToCap()
        return true
    }

    fun removeAt(index: Int): Boolean {
        if (index !in _entries.indices) return false
        _entries.removeAt(index)
        return true
    }

    fun togglePin(index: Int): Boolean {
        val entry = _entries.getOrNull(index) ?: return false
        _entries[index] = entry.copy(pinned = !entry.pinned)
        // Unpinning can push the unpinned count over the cap.
        trimToCap()
        return true
    }

    fun clear() {
        _entries.clear()
    }

    /** Replace the whole list (used when loading persisted history). */
    fun replaceAll(entries: List<ClipboardEntry>) {
        _entries.clear()
        // Re-apply the length cap defensively: entries persisted by an older
        // version (or a hand-edited prefs file) may exceed it.
        for (entry in entries) {
            val (stored, truncated) = truncate(entry.text)
            _entries.add(entry.copy(text = stored, truncated = entry.truncated || truncated))
        }
        trimToCap()
    }

    private fun trimToCap() {
        while (_entries.count { !it.pinned } > MAX_ENTRIES) {
            val idx = _entries.indexOfLast { !it.pinned }
            if (idx < 0) break
            _entries.removeAt(idx)
        }
    }
}
