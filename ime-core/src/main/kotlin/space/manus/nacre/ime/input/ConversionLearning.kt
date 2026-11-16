package space.manus.nacre.ime.input

/**
 * Pure logic for per-(reading, surface) selection learning: how often the
 * user has picked a surface for a reading, and when they last did.
 *
 * Why this exists next to the older `userBoost` counter in
 * [NacreDictionary]: that counter is a bare count with a global 10%-per-
 * session decay — it cannot tell "picked three times yesterday" from
 * "picked three times last year", and the only recency signal was the
 * separate 200-entry recent-history window. This store keeps an explicit
 * selection count plus a monotonic sequence number of the last selection,
 * so the ranking boost can combine frequency AND recency per entry while
 * staying bounded. The legacy counter is still honored (see [boost]):
 * counts learned before this store existed keep their value.
 *
 * Storage format (one entry per line, tab-separated, written by
 * [NacreDictionary] into the existing `nacre_user_dict` store):
 *   reading \t surface \t count \t lastSeq
 * Readings/surfaces are sanitized on record (tab/newline → space), the
 * same discipline the user-dictionary flat file uses, so a stored line
 * can never split into phantom entries.
 *
 * Everything here is allocation-light and O(1) per lookup: the map is
 * loaded once into memory, mutated on selection, and persisted through
 * the dictionary's existing debounced save — never per keystroke.
 */
internal object ConversionLearning {

    /** One learned (reading → surface) pair. Immutable; record() replaces. */
    data class Entry(
        val reading: String,
        val surface: String,
        val count: Int,
        val lastSeq: Long,
    )

    /** Hard cap on stored pairs; the weakest entries are pruned first. */
    const val MAX_ENTRIES = 5000

    /** Total unigram boost ceiling (cost points) from this signal. */
    const val MAX_BOOST = 7000

    /**
     * Selections of the same (reading, surface) required before that
     * surface may be promoted above the zenz neural candidate. Three is
     * the classic IME compromise: one pick can be an accident or a
     * one-off context, two can be a coincidence of the same context, but
     * three deliberate picks of the same surface for the same reading is
     * a stable personal preference. (Defined and tested here; the zenz
     * insertion sites currently keep zenz first regardless — see the PR
     * description for that decision.)
     */
    const val PROMOTE_OVER_ZENZ_MIN_COUNT = 3

    /** Map key with a separator that cannot appear in sanitized text ambiguously. */
    fun key(reading: String, surface: String): String = "$reading\u0001$surface"

    /** Strip the flat-file delimiters, mirroring registerUserWord(). */
    fun sanitize(text: String): String =
        text.replace('\t', ' ').replace('\n', ' ').trim()

    /**
     * Record one selection of [surface] for [reading] at sequence [seq].
     * Returns the updated entry, or null when the pair is not learnable
     * (blank after sanitizing). Prunes to [MAX_ENTRIES] when oversized.
     */
    fun record(
        entries: MutableMap<String, Entry>,
        reading: String,
        surface: String,
        seq: Long,
    ): Entry? {
        val r = sanitize(reading)
        val s = sanitize(surface)
        if (r.isEmpty() || s.isEmpty()) return null
        val k = key(r, s)
        val prev = entries[k]
        val next = Entry(r, s, (prev?.count ?: 0) + 1, seq)
        entries[k] = next
        if (entries.size > MAX_ENTRIES) prune(entries)
        return next
    }

    /** Drop the weakest entries (lowest count, then oldest) down to the cap. */
    fun prune(entries: MutableMap<String, Entry>) {
        if (entries.size <= MAX_ENTRIES) return
        val weakest = entries.entries
            .sortedWith(compareBy({ it.value.count }, { it.value.lastSeq }))
            .take(entries.size - MAX_ENTRIES)
        for (e in weakest) entries.remove(e.key)
    }

    /**
     * Effective selection count: the better of the legacy `userBoost`
     * counter and this store's count. Both are incremented by the same
     * recordSelection() call, but the legacy counter predates this store,
     * so a long-time user's history must not be reset to zero.
     */
    fun effectiveCount(legacyCount: Int, entry: Entry?): Int =
        maxOf(legacyCount, entry?.count ?: 0)

    /**
     * Frequency component — deliberately the same saturating table the
     * legacy unigram boost used (1st pick 2000 … 5th+ 5500), so rankings
     * for equally-fresh entries do not shift when this store takes over.
     */
    fun countBoost(count: Int): Int = when {
        count >= 5 -> 5500
        count == 4 -> 5200
        count == 3 -> 4500
        count == 2 -> 3500
        count == 1 -> 2000
        else -> 0
    }

    /**
     * Recency component: how close the last selection is to [currentSeq]
     * (the global selection counter). Measured in selections, not wall
     * time — an IME that is unused for a month should not forget, and a
     * burst of typing should age entries at the pace the user actually
     * makes competing selections.
     */
    fun recencyBoost(entry: Entry?, currentSeq: Long): Int {
        if (entry == null) return 0
        val age = (currentSeq - entry.lastSeq).coerceAtLeast(0)
        return when {
            age <= 10L -> 1200
            age <= 50L -> 800
            age <= 200L -> 400
            else -> 0
        }
    }

    /** Combined bounded boost for one candidate. */
    fun boost(legacyCount: Int, entry: Entry?, currentSeq: Long): Int =
        (countBoost(effectiveCount(legacyCount, entry)) +
            recencyBoost(entry, currentSeq)).coerceAtMost(MAX_BOOST)

    /** Whether [effectiveCount] selections justify outranking zenz. */
    fun shouldPromoteOverZenz(effectiveCount: Int): Boolean =
        effectiveCount >= PROMOTE_OVER_ZENZ_MIN_COUNT

    /** Serialize, freshest first, capped at [MAX_ENTRIES]. */
    fun serialize(entries: Collection<Entry>): String =
        entries
            .sortedWith(compareByDescending<Entry> { it.lastSeq }.thenByDescending { it.count })
            .take(MAX_ENTRIES)
            .joinToString("\n") { "${it.reading}\t${it.surface}\t${it.count}\t${it.lastSeq}" }

    /** Parse the flat format; malformed lines are skipped, never fatal. */
    fun parse(data: String): Map<String, Entry> {
        val out = LinkedHashMap<String, Entry>()
        for (line in data.split('\n')) {
            if (line.isBlank()) continue
            val parts = line.split('\t')
            if (parts.size != 4) continue
            val count = parts[2].toIntOrNull() ?: continue
            val seq = parts[3].toLongOrNull() ?: continue
            if (parts[0].isEmpty() || parts[1].isEmpty() || count <= 0) continue
            val entry = Entry(parts[0], parts[1], count, seq)
            out[key(entry.reading, entry.surface)] = entry
        }
        return out
    }
}
