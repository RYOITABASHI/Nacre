package space.manus.nacre.ime.input

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

class EnglishMatcher(private val context: Context) {
    private val englishDict = HashMap<String, MutableList<DictEntry>>(5000)
    private val englishFullDict = HashMap<String, MutableList<DictEntry>>(25000)
    private var englishSortedKeys: Array<String> = emptyArray()
    private val englishBigramBoost = ConcurrentHashMap<String, Int>(200)
    private var lastCommittedEnglish: String = ""
    private val romajiEnglishIndex = HashMap<String, MutableList<DictEntry>>(500)

    fun load() {
        loadEnglishDict()
        buildRomajiEnglishIndex()
        loadEnglishFullDict()
    }

    /**
     * Predict English words from prefix input.
     * Returns autocomplete candidates sorted by cost (frequency).
     */
    fun predict(prefix: String, limit: Int = 20): List<ConversionCandidate> {
        if (prefix.length < 1) return emptyList()
        val prefixLower = prefix.lowercase()
        val results = mutableListOf<ConversionCandidate>()

        // 1. Exact match
        val exact = englishFullDict[prefixLower]
        if (exact != null) {
            for (entry in exact) {
                results.add(ConversionCandidate(
                    surface = entry.surface,
                    reading = prefix,
                    cost = entry.cost - 2000,  // Strong bonus for exact match
                ))
            }
        }

        // 2. Prefix match via binary search on sorted keys
        val startIdx = englishSortedKeys.binarySearchInsertionPoint(prefixLower)
        var count = 0
        for (i in startIdx until englishSortedKeys.size) {
            val key = englishSortedKeys[i]
            if (!key.startsWith(prefixLower)) break
            if (key == prefixLower) continue  // Already handled as exact
            val entries = englishFullDict[key] ?: continue
            for (entry in entries.take(2)) {
                val lengthPenalty = (key.length - prefixLower.length) * 100
                results.add(ConversionCandidate(
                    surface = entry.surface,
                    reading = prefix,
                    cost = entry.cost + lengthPenalty,
                ))
            }
            count++
            if (count >= limit * 2) break
        }

        // 3. Spell correction (edit distance 1) for inputs >= 3 chars
        if (results.size < 5 && prefixLower.length >= 3) {
            val corrections = spellCorrect(prefixLower, limit = 5)
            for (c in corrections) {
                if (results.none { it.surface.equals(c.surface, ignoreCase = true) }) {
                    results.add(c)
                }
            }
        }

        // 4. Apply English bigram boost
        if (lastCommittedEnglish.isNotEmpty()) {
            for (i in results.indices) {
                val bigramKey = "${lastCommittedEnglish.lowercase()}→${results[i].surface.lowercase()}"
                val boost = englishBigramBoost[bigramKey] ?: 0
                if (boost > 0) {
                    results[i] = results[i].copy(cost = results[i].cost - minOf(boost * 800, 3000))
                }
            }
        }

        return results.sortedBy { it.cost }.take(limit)
    }

    /**
     * Record English word selection for bigram learning.
     */
    fun recordSelection(word: String) {
        if (lastCommittedEnglish.isNotEmpty()) {
            val key = "${lastCommittedEnglish.lowercase()}→${word.lowercase()}"
            englishBigramBoost.merge(key, 1) { old, _ -> minOf(old + 1, 5) }
        }
        lastCommittedEnglish = word
    }

    /**
     * Match English words via hiragana reading.
     * e.g. "ぐーぐる" → "Google"
     */
    fun match(kana: String, limit: Int = 10): List<ConversionCandidate> {
        if (limit <= 0 || kana.length < 2) return emptyList()
        val results = mutableListOf<ConversionCandidate>()

        // 1. Exact match — no penalty
        val exact = englishDict[kana]
        if (exact != null) {
            for (entry in exact.take(limit)) {
                results.add(ConversionCandidate(
                    surface = entry.surface,
                    reading = kana,
                    cost = entry.cost,
                ))
            }
        }

        // 2. Prefix match — penalty proportional to remaining characters
        // e.g. "あっぷ"(3) matching "あっぷる"(4): penalty = (4-3)/4 * 800 = 200
        // e.g. "あ"(1) matching "あっぷる"(4): penalty = (4-1)/4 * 800 = 600
        if (results.size < limit) {
            for ((key, entries) in englishDict) {
                if (key.startsWith(kana) && key != kana) {
                    val matchRatio = kana.length.toFloat() / key.length
                    val penalty = ((1f - matchRatio) * 800).toInt()
                    for (entry in entries.take(1)) {
                        results.add(ConversionCandidate(
                            surface = entry.surface,
                            reading = key,
                            cost = entry.cost + penalty,
                        ))
                        if (results.size >= limit) return results.sortedBy { it.cost }
                    }
                }
            }
        }

        return results.sortedBy { it.cost }
    }

    /**
     * Match raw romaji input against English words.
     * e.g. "goo" matches "Google", "good"; "lin" matches "LINE", "Linux"
     */
    fun romajiMatch(romaji: String, limit: Int = 10): List<ConversionCandidate> {
        if (limit <= 0 || romaji.length < 2) return emptyList()
        val romajiLower = romaji.lowercase()
        val results = mutableListOf<ConversionCandidate>()

        for ((key, entries) in romajiEnglishIndex) {
            if (key.startsWith(romajiLower)) {
                for (entry in entries.take(1)) {
                    results.add(ConversionCandidate(
                        surface = entry.surface,
                        reading = romaji,
                        cost = entry.cost + if (key == romajiLower) 0 else 500,
                    ))
                    if (results.size >= limit) return results.sortedBy { it.cost }
                }
            }
        }

        return results.sortedBy { it.cost }
    }

    /**
     * Spell correction via edit distance 1 (deletion, substitution, insertion, transposition).
     */
    fun spellCorrect(input: String, limit: Int = 5): List<ConversionCandidate> {
        val results = mutableListOf<ConversionCandidate>()
        val seen = mutableSetOf<String>()

        // Deletions: remove one char
        for (i in input.indices) {
            val candidate = input.removeRange(i, i + 1)
            if (candidate.length >= 2 && seen.add(candidate)) {
                val entries = englishFullDict[candidate]
                if (entries != null) {
                    for (e in entries.take(1)) {
                        results.add(ConversionCandidate(e.surface, input, e.cost + 3000))
                    }
                }
            }
        }

        // Substitutions: replace one char
        for (i in input.indices) {
            for (c in 'a'..'z') {
                if (c == input[i]) continue
                val candidate = input.replaceRange(i, i + 1, c.toString())
                if (seen.add(candidate)) {
                    val entries = englishFullDict[candidate]
                    if (entries != null) {
                        for (e in entries.take(1)) {
                            results.add(ConversionCandidate(e.surface, input, e.cost + 3000))
                        }
                    }
                }
            }
            if (results.size >= limit) break
        }

        // Transpositions: swap adjacent chars
        for (i in 0 until input.length - 1) {
            val candidate = buildString {
                append(input, 0, i)
                append(input[i + 1])
                append(input[i])
                if (i + 2 < input.length) append(input, i + 2, input.length)
            }
            if (seen.add(candidate)) {
                val entries = englishFullDict[candidate]
                if (entries != null) {
                    for (e in entries.take(1)) {
                        results.add(ConversionCandidate(e.surface, input, e.cost + 2500))
                    }
                }
            }
        }

        // Edit distance 2: double deletion (for 5+ char inputs, limited scope)
        if (results.size < limit && input.length >= 5) {
            outer@ for (i in input.indices) {
                val del1 = input.removeRange(i, i + 1)
                for (j in del1.indices) {
                    val del2 = del1.removeRange(j, j + 1)
                    if (del2.length >= 2 && seen.add(del2)) {
                        val entries = englishFullDict[del2]
                        if (entries != null) {
                            for (e in entries.take(1)) {
                                results.add(ConversionCandidate(e.surface, input, e.cost + 4500))
                            }
                            if (results.size >= limit) break@outer
                        }
                    }
                }
            }
        }

        return results.sortedBy { it.cost }.take(limit)
    }

    private fun loadEnglishDict() {
        try {
            context.assets.open("dict/english_words.tsv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val parts = line.split('\t')
                        if (parts.size >= 2) {
                            val romaji = parts[0]
                            val english = parts[1]
                            val cost = if (parts.size >= 3) parts[2].toIntOrNull() ?: 8000 else 8000
                            englishDict.getOrPut(romaji) { mutableListOf() }
                                .add(DictEntry(english, cost))
                        }
                    }
                }
            }
            Log.i("EnglishMatcher", "English dict loaded: ${englishDict.size} entries")
        } catch (e: Exception) {
            Log.i("EnglishMatcher", "No English dictionary found (optional)")
        }
    }

    private fun loadEnglishFullDict() {
        try {
            context.assets.open("dict/english_full.tsv").use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    reader.forEachLine { line ->
                        if (line.isBlank() || line.startsWith('#')) return@forEachLine
                        val parts = line.split('\t')
                        if (parts.size >= 3) {
                            val key = parts[0]       // lowercase key
                            val surface = parts[1]    // display form (may have mixed case)
                            val cost = parts[2].toIntOrNull() ?: 8000
                            englishFullDict.getOrPut(key) { mutableListOf() }
                                .add(DictEntry(surface, cost))
                        }
                    }
                }
            }
            englishSortedKeys = englishFullDict.keys.toTypedArray().also { it.sort() }
            Log.i("EnglishMatcher", "English full dict loaded: ${englishFullDict.size} keys, ${englishSortedKeys.size} sorted")
        } catch (e: Exception) {
            Log.i("EnglishMatcher", "No english_full.tsv found (optional)")
        }
    }

    private fun buildRomajiEnglishIndex() {
        for ((_, entries) in englishDict) {
            for (entry in entries) {
                val key = entry.surface.lowercase()
                romajiEnglishIndex.getOrPut(key) { mutableListOf() }.add(entry)
            }
        }
        Log.i("EnglishMatcher", "Romaji English index: ${romajiEnglishIndex.size} entries")
    }

    /** Binary search for insertion point in sorted array. */
    private fun Array<String>.binarySearchInsertionPoint(prefix: String): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (this[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
