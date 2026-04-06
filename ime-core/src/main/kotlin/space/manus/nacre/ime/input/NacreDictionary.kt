package space.manus.nacre.ime.input

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import space.manus.nacre.ai.KenLmScorer
import space.manus.nacre.ime.input.DictionaryManager.Companion.isContentWord

/**
 * Nacre Japanese Dictionary with POS-aware Viterbi conversion.
 *
 * Uses Mozc OSS dictionary with full 2670 POS IDs and the original
 * 2670×2670 connection cost matrix for maximum conversion accuracy.
 *
 * Dictionary format: reading\tsurface\tleft_id\tright_id\tcost
 * Connection matrix: binary 2670×2670 int16 (connection.bin)
 *
 * Mozc POS ID ranges:
 *   0=BOS/EOS, 2..11=フィラー, 12..28=副詞, 29..267=助動詞,
 *   268..433=助詞, 434..1840=動詞, 1841..2193=名詞,
 *   2194..2588=形容詞, 2589..2590=感動詞, 2591..2593=接続詞,
 *   2594..2640=接頭詞, 2641..2656=記号, 2657..2669=連体詞
 */
class NacreDictionary(private val context: Context) : DictionaryProvider {

    // Delegated dictionary loading, lookup, connection cost
    val dictManager = DictionaryManager(context)

    // Delegated Viterbi conversion engine
    val viterbiEngine = ViterbiEngine(dictManager) { kenLmScorer }

    // Convenience accessors for dictManager fields (reduces diff in this transitional step)
    private inline val dict get() = dictManager.dict
    private inline val sortedReadings get() = dictManager.sortedReadings

    // Delegated user learning, boost, context, persistence
    val userLearner = UserLearner(context, dictManager) { kenLmScorer }

    // English word dictionary (hiragana reading → English words)
    private val englishDict = HashMap<String, MutableList<DictEntry>>(5000)

    // Full English dictionary: lowercase key → list of DictEntry (surface may have mixed case)
    private val englishFullDict = HashMap<String, MutableList<DictEntry>>(25000)
    // Sorted keys for binary-search prefix matching
    private var englishSortedKeys: Array<String> = emptyArray()
    // English word learning: "prevWord→word" → count (bigram boost)
    private val englishBigramBoost = ConcurrentHashMap<String, Int>(200)
    private var lastCommittedEnglish: String = ""

    // KenLM 5-gram language model scorer (optional, loaded from ime-ai)
    @Volatile
    var kenLmScorer: KenLmScorer? = null

    private var loaded = false

    /** Total number of dictionary entries loaded (for debug display) */
    val entryCount: Int get() = dictManager.entryCount

    fun load() {
        if (loaded) return

        // Load dictionaries, connection matrix, static bigrams via DictionaryManager
        dictManager.load()

        loadEnglishDict()
        buildRomajiEnglishIndex()
        loadEnglishFullDict()

        // Load user learning data (boost, user dict, phrase memory, decay)
        userLearner.load()

        loaded = true

        // Wire ViterbiEngine callbacks to UserLearner
        viterbiEngine.boostFunction = userLearner::applyBoost
        viterbiEngine.posContextCostFunction = userLearner::posContextCost
    }

    // --- DictionaryProvider implementation ---

    /** Sync mutable context to ViterbiEngine before calling its methods */
    private fun syncViterbiContext() {
        viterbiEngine.lastRightGroup = userLearner.lastRightGroup
        viterbiEngine.committedContext = userLearner.committedContext
    }

    override fun convert(kana: String): List<ConversionCandidate> {
        if (!loaded || kana.isEmpty()) return emptyList()
        syncViterbiContext()

        val results = mutableListOf<ConversionCandidate>()
        val seen = mutableSetOf<String>()

        fun addUnique(candidates: List<ConversionCandidate>) {
            for (c in candidates) {
                if (seen.add(c.surface)) results.add(c)
            }
        }

        // 1. Exact match FIRST (single-word candidates — these should rank highest)
        addUnique(viterbiEngine.exactMatch(kana))

        // 2. Viterbi multi-word conversion (best segmentation)
        addUnique(viterbiEngine.search(kana))

        // 2.5 Kana variant conversion (を→お/うぉ, ぢ→じ, づ→ず etc.)
        // Google日本語入力方式: ローマ字テーブルは標準のまま、変換段階で読み替え候補を生成
        val kanaVariants = viterbiEngine.generateKanaVariants(kana)
        for (variant in kanaVariants) {
            val variantExact = viterbiEngine.exactMatch(variant)
            for (c in variantExact) {
                if (seen.add(c.surface)) results.add(c.copy(cost = c.cost + 300))
            }
            val variantViterbi = viterbiEngine.search(variant).take(5)
            for (c in variantViterbi) {
                if (seen.add(c.surface)) results.add(c.copy(cost = c.cost + 500))
            }
        }

        // 3. Partial segmentation for diversity (with POS connection cost)
        if (results.size < 12 && kana.length >= 3) {
            for (splitAt in (kana.length - 1) downTo 2) {
                val head = kana.substring(0, splitAt)
                val tail = kana.substring(splitAt)
                val headEntries = dict[head]?.take(3) ?: continue
                val tailEntries = dict[tail]?.take(3)
                if (tailEntries != null) {
                    for (h in headEntries) {
                        for (t in tailEntries) {
                            val combined = h.surface + t.surface
                            if (seen.add(combined)) {
                                results.add(ConversionCandidate(
                                    surface = combined, reading = kana,
                                    cost = h.cost + t.cost + getConnectionCost(h.rightGroup, t.leftGroup),
                                ))
                            }
                        }
                        // Hiragana tail
                        if (tail.length <= 4 && h.surface != head) {
                            val combo = h.surface + tail
                            if (seen.add(combo)) {
                                results.add(ConversionCandidate(surface = combo, reading = kana, cost = h.cost + 3500))
                            }
                        }
                    }
                }
                if (results.size >= 15) break
            }
        }

        // 4. 3-way segmentation for medium inputs — skip for long text (Viterbi handles it)
        if (results.size < 15 && kana.length in 6..14) {
            outer@ for (s1 in 2..(kana.length - 4)) {
                val seg1 = kana.substring(0, s1)
                val entries1 = dict[seg1]?.take(3) ?: continue
                for (s2 in (s1 + 2)..(kana.length - 2)) {
                    val seg2 = kana.substring(s1, s2)
                    val seg3 = kana.substring(s2)
                    val entries2 = dict[seg2]?.take(3) ?: continue
                    val entries3 = dict[seg3]?.take(3) ?: continue
                    for (e1 in entries1) {
                        for (e2 in entries2) {
                            for (e3 in entries3) {
                                val combined = e1.surface + e2.surface + e3.surface
                                if (seen.add(combined)) {
                                    val cost = e1.cost + e2.cost + e3.cost +
                                        getConnectionCost(e1.rightGroup, e2.leftGroup) +
                                        getConnectionCost(e2.rightGroup, e3.leftGroup)
                                    results.add(ConversionCandidate(surface = combined, reading = kana, cost = cost))
                                }
                                if (results.size >= 25) break@outer
                            }
                        }
                    }
                }
            }
        }

        // 5. Katakana / Hiragana / Half-width katakana as-is
        val katakana = viterbiEngine.hiraganaToKatakana(kana)
        if (katakana != kana && seen.add(katakana)) {
            val katakanaCost = viterbiEngine.estimateKatakanaCost(kana, results)
            results.add(ConversionCandidate(surface = katakana, reading = kana, cost = katakanaCost))
        }
        // Half-width katakana (e.g. for older systems, game text, stylistic use)
        if (kana.length >= 2) {
            val halfKatakana = viterbiEngine.toHalfWidthKatakana(kana)
            if (halfKatakana != kana && halfKatakana != katakana && seen.add(halfKatakana)) {
                val maxCost = results.maxOfOrNull { it.cost } ?: 5000
                results.add(ConversionCandidate(surface = halfKatakana, reading = kana, cost = maxCost + 800))
            }
        }
        if (seen.add(kana)) {
            val maxCost = results.maxOfOrNull { it.cost } ?: 5000
            results.add(ConversionCandidate(surface = kana, reading = kana, cost = maxCost + 200))
        }

        // Sort: apply user boost + POS context + KenLM rescoring
        val boosted = results.map { c ->
            var cost = userLearner.applyBoost(c.reading, c.surface, c.cost)
            cost = userLearner.posContextCost(c.reading, c.surface, cost)
            c.copy(cost = cost)
        }.toMutableList()

        userLearner.kenLmRescore(boosted)

        // Post-rescore filter: remove candidates with catastrophically bad LM scores
        if (kenLmScorer?.isReady() == true && boosted.size > 3) {
            val bestCost = boosted.minOf { it.cost }
            boosted.removeAll { it.cost > bestCost + 10000 && it.cost > bestCost * 3 }
        }

        return boosted.sortedBy { it.cost }.take(if (kana.length <= 3) 40 else 30)
    }

    override fun predict(kana: String, romaji: String): List<ConversionCandidate> {
        if (!loaded || kana.isEmpty()) return emptyList()
        syncViterbiContext()

        val results = mutableListOf<ConversionCandidate>()
        val seen = mutableSetOf<String>()

        fun addUnique(candidates: List<ConversionCandidate>) {
            for (c in candidates) {
                if (seen.add(c.surface)) results.add(c)
            }
        }

        // 0. Recent history matches (with prefix-length penalty)
        val historyMatches = userLearner.recentHistory.filter {
            it.reading == kana || it.reading.startsWith(kana)
        }.map { h ->
            if (h.reading == kana) {
                h // Exact match — no penalty
            } else {
                // Prefix match: penalize proportionally to how much is unmatched.
                // Short input (1-2 chars): high penalty to avoid history domination
                // Longer input (3+ chars): lower penalty since it's more likely intentional
                val unmatched = h.reading.length - kana.length
                val perCharPenalty = if (kana.length <= 2) 2000 else 1200
                h.copy(cost = h.cost + unmatched * perCharPenalty)
            }
        }.take(3)
        addUnique(historyMatches)

        // 1. Exact match FIRST (single-word candidates rank highest)
        addUnique(viterbiEngine.exactMatch(kana))

        // 2. Viterbi multi-word conversion
        addUnique(viterbiEngine.search(kana).take(8))

        // 2.5 Kana variant conversion (を→お/うぉ, ぢ→じ etc.)
        val kanaVariants = viterbiEngine.generateKanaVariants(kana)
        for (variant in kanaVariants) {
            val variantResults = viterbiEngine.exactMatch(variant) + viterbiEngine.search(variant).take(3)
            for (c in variantResults) {
                if (seen.add(c.surface)) results.add(c.copy(cost = c.cost + 500))
            }
        }

        // 3. Prefix match
        if (results.size < 15) {
            addUnique(prefixMatch(kana, limit = 15 - results.size))
        }

        // 4. Partial segmentation with hiragana tail preference
        if (results.size < 10 && kana.length >= 3) {
            for (splitAt in 2 until kana.length) {
                val head = kana.substring(0, splitAt)
                val tail = kana.substring(splitAt)
                val headEntries = dict[head]?.take(3) ?: continue
                val tailEntries = dict[tail]?.take(3)
                if (tailEntries != null) {
                    for (h in headEntries) {
                        for (t in tailEntries) {
                            val combined = h.surface + t.surface
                            if (seen.add(combined)) {
                                results.add(ConversionCandidate(
                                    surface = combined,
                                    reading = kana,
                                    cost = h.cost + t.cost + getConnectionCost(h.rightGroup, t.leftGroup),
                                ))
                            }
                            if (results.size >= 25) break
                        }
                        // Also add hiragana-tail variant: e.g. "スクショ" + "した"
                        if (tail.length <= 3 && h.surface != head) {
                            val hiraganaCombo = h.surface + tail
                            if (seen.add(hiraganaCombo)) {
                                results.add(ConversionCandidate(
                                    surface = hiraganaCombo,
                                    reading = kana,
                                    cost = h.cost + 4000,
                                ))
                            }
                        }
                        if (results.size >= 25) break
                    }
                }
                if (results.size >= 25) break
            }
        }

        // 5. English word candidates (hiragana reading match)
        if (results.size < 20) {
            addUnique(englishMatch(kana, limit = 5))
        }

        // 6. Romaji-based English candidates (e.g. "goo" → "Google")
        if (romaji.isNotEmpty() && romaji.length >= 2) {
            addUnique(romajiEnglishMatch(romaji, limit = 5))
        }

        // 7. Typo correction: swap adjacent kana, common misreadings
        if (results.size < 15 && kana.length >= 3) {
            addUnique(typoCorrection(kana, limit = 5))
        }

        // 8. Always ensure katakana, half-width katakana, and hiragana as-is candidates exist
        if (kana.length >= 2) {
            val katakana = viterbiEngine.hiraganaToKatakana(kana)
            if (katakana != kana && seen.add(katakana)) {
                val katakanaCost = viterbiEngine.estimateKatakanaCost(kana, results)
                results.add(ConversionCandidate(surface = katakana, reading = kana, cost = katakanaCost))
            }
            val halfKatakana = viterbiEngine.toHalfWidthKatakana(kana)
            if (halfKatakana != kana && halfKatakana != katakana && seen.add(halfKatakana)) {
                val maxCost = results.maxOfOrNull { it.cost } ?: 5000
                results.add(ConversionCandidate(surface = halfKatakana, reading = kana, cost = maxCost + 800))
            }
            if (seen.add(kana)) {
                val maxCost = results.maxOfOrNull { it.cost } ?: 5000
                results.add(ConversionCandidate(surface = kana, reading = kana, cost = maxCost + 200))
            }
        }

        // Final sort: apply user boost + POS context + KenLM to all candidates
        val boosted = results.map { c ->
            var cost = userLearner.applyBoost(c.reading, c.surface, c.cost)
            cost = userLearner.posContextCost(c.reading, c.surface, cost)
            c.copy(cost = cost)
        }.toMutableList()

        userLearner.kenLmRescore(boosted)

        return boosted.sortedBy { it.cost }.take(if (kana.length <= 3) 40 else 25)
    }

    override fun recordSelection(candidate: ConversionCandidate) {
        userLearner.recordSelection(candidate)
    }

    fun updateContext(surface: String) {
        userLearner.updateContext(surface)
    }

    fun predictNextWord(limit: Int = 8): List<ConversionCandidate> {
        return userLearner.predictNextWord(limit)
    }

    fun flushPendingSave() {
        userLearner.flushPendingSave()
    }

    // --- Connection cost (delegated to DictionaryManager) ---

    private fun getConnectionCost(prevRightId: Int, currLeftId: Int): Int =
        dictManager.getConnectionCost(prevRightId, currLeftId)

    // --- Internal ---

    private fun prefixMatch(kana: String, limit: Int): List<ConversionCandidate> {
        if (limit <= 0) return emptyList()

        val results = mutableListOf<ConversionCandidate>()

        var idx = sortedReadings.binarySearch(kana).let {
            if (it >= 0) it else -(it + 1)
        }

        // For short inputs, show more candidates per reading to increase diversity
        val takePerReading = when {
            kana.length <= 1 -> 5
            kana.length <= 2 -> 4
            else -> 3
        }
        // Prefer readings that are close in length to input (more likely what user wants)
        val maxExtraChars = if (kana.length <= 2) 6 else 8

        while (idx < sortedReadings.size && sortedReadings[idx].startsWith(kana)) {
            val reading = sortedReadings[idx]
            val extraChars = reading.length - kana.length
            if (reading != kana && extraChars <= maxExtraChars) {
                val entries = dict[reading]
                if (entries == null) { idx++; continue }
                for (entry in entries.take(takePerReading)) {
                    var cost = userLearner.applyBoost(reading, entry.surface, entry.cost)
                    cost = userLearner.posContextCost(reading, entry.surface, cost)
                    // Graduated penalty: first few extra chars are cheap, gets expensive
                    cost += when (extraChars) {
                        1 -> 150
                        2 -> 350
                        3 -> 600
                        4 -> 1000
                        else -> extraChars * 300
                    }
                    // Bonus for content words (users usually want kanji, not particles)
                    if (isContentWord(entry.leftGroup) && kana.length >= 2) cost -= 400
                    results.add(
                        ConversionCandidate(
                            surface = entry.surface,
                            reading = reading,
                            cost = cost,
                        ),
                    )
                    if (results.size >= limit) {
                        return results.sortedBy { it.cost }
                    }
                }
            }
            idx++
        }

        return results.sortedBy { it.cost }
    }

    // --- English word matching ---

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
            Log.i("NacreDictionary", "English dict loaded: ${englishDict.size} entries")
        } catch (e: Exception) {
            Log.i("NacreDictionary", "No English dictionary found (optional)")
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
            Log.i("NacreDictionary", "English full dict loaded: ${englishFullDict.size} keys, ${englishSortedKeys.size} sorted")
        } catch (e: Exception) {
            Log.i("NacreDictionary", "No english_full.tsv found (optional)")
        }
    }

    /**
     * Predict English words from prefix input.
     * Returns autocomplete candidates sorted by cost (frequency).
     */
    override fun predictEnglish(prefix: String, limit: Int): List<ConversionCandidate> {
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
    override fun recordEnglishSelection(word: String) {
        if (lastCommittedEnglish.isNotEmpty()) {
            val key = "${lastCommittedEnglish.lowercase()}→${word.lowercase()}"
            val count = englishBigramBoost.merge(key, 1) { old, _ -> minOf(old + 1, 5) } ?: 1
        }
        lastCommittedEnglish = word
    }

    /**
     * Spell correction via edit distance 1 (deletion, substitution, insertion, transposition).
     */
    private fun spellCorrect(input: String, limit: Int): List<ConversionCandidate> {
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

    private fun englishMatch(kana: String, limit: Int): List<ConversionCandidate> {
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

    // Romaji→English reverse index: maps lowercase English word prefix to entries
    // Built from englishDict: e.g. "google" → DictEntry("Google", 4000)
    private val romajiEnglishIndex = HashMap<String, MutableList<DictEntry>>(500)

    private fun buildRomajiEnglishIndex() {
        for ((_, entries) in englishDict) {
            for (entry in entries) {
                val key = entry.surface.lowercase()
                romajiEnglishIndex.getOrPut(key) { mutableListOf() }.add(entry)
            }
        }
        Log.i("NacreDictionary", "Romaji English index: ${romajiEnglishIndex.size} entries")
    }

    /**
     * Match raw romaji input against English words.
     * e.g. "goo" matches "Google", "good"; "lin" matches "LINE", "Linux"
     */
    private fun romajiEnglishMatch(romaji: String, limit: Int): List<ConversionCandidate> {
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

    // --- Typo correction ---

    /**
     * Generate typo-corrected candidates by:
     * 1. Swapping adjacent kana characters (transposition)
     * 2. Common misreading patterns (ふいんき→ふんいき etc.)
     */
    private fun typoCorrection(kana: String, limit: Int): List<ConversionCandidate> {
        if (limit <= 0 || kana.length < 3) return emptyList()
        val results = mutableListOf<ConversionCandidate>()
        val seen = mutableSetOf<String>()

        // 1. Adjacent character transposition
        for (i in 0 until kana.length - 1) {
            val swapped = buildString {
                append(kana, 0, i)
                append(kana[i + 1])
                append(kana[i])
                append(kana, i + 2, kana.length)
            }
            if (swapped != kana && seen.add(swapped)) {
                val entries = dict[swapped]
                if (entries != null) {
                    for (entry in entries.take(2)) {
                        results.add(ConversionCandidate(
                            surface = entry.surface,
                            reading = swapped,
                            cost = entry.cost + 2000, // Penalty for typo
                        ))
                        if (results.size >= limit) return results.sortedBy { it.cost }
                    }
                }
                // Also try Viterbi on the swapped reading
                val viterbi = viterbiEngine.search(swapped)
                for (v in viterbi.take(1)) {
                    if (seen.add(v.surface)) {
                        results.add(v.copy(cost = v.cost + 2000))
                        if (results.size >= limit) return results.sortedBy { it.cost }
                    }
                }
            }
        }

        // 2. Common misreading/mistyping patterns
        val corrections = mapOf(
            // === 定番の誤読 ===
            "ふいんき" to "ふんいき",        // 雰囲気
            "たいく" to "たいいく",          // 体育
            "がいしゅつ" to "きしゅつ",       // 既出
            "ぜいいん" to "ぜんいん",        // 全員
            "いちよう" to "いちおう",        // 一応
            "そうゆう" to "そういう",        // そういう
            "こんにちわ" to "こんにちは",
            "こんばんわ" to "こんばんは",
            "づつ" to "ずつ",              // ～ずつ
            "しゅみれーしょん" to "しみゅれーしょん", // シミュレーション
            "うるおぼえ" to "うろおぼえ",     // うろ覚え
            "ていかく" to "てきかく",        // 的確
            "げいいん" to "げんいん",        // 原因
            "がいいん" to "げんいん",        // 原因
            "えんえん" to "えんえん",        // 延々（「永遠」と混同されがち。自己マップだが辞書引きで候補出す）
            "かんぺき" to "かんぺき",        // 完璧
            "ひつぜん" to "ひつぜん",
            "ぜったい" to "ぜったい",
            // === 助詞「は」「へ」「を」の誤り ===
            "わたしわ" to "わたしは",
            "きょうわ" to "きょうは",
            "ぼくわ" to "ぼくは",
            "あなたわ" to "あなたは",
            "それわ" to "それは",
            "これわ" to "これは",
            "あれわ" to "あれは",
            "どれわ" to "どれは",
            "だれわ" to "だれは",
            "なにわ" to "なには",
            "どこえ" to "どこへ",
            "こっちえ" to "こっちへ",
            "そっちえ" to "そっちへ",
            "あっちえ" to "あっちへ",
            "うちえ" to "うちへ",
            "がっこうえ" to "がっこうへ",
            "かいしゃえ" to "かいしゃへ",
            // === 長音の誤入力 ===
            "とうり" to "とおり",            // 通り
            "おうきい" to "おおきい",        // 大きい
            "おうい" to "おおい",            // 多い
            "こうり" to "こおり",            // 氷
            "とうい" to "とおい",            // 遠い
            "おうきな" to "おおきな",        // 大きな
            // === カタカナ語の誤読 ===
            "こみにけーしょん" to "こみゅにけーしょん", // コミュニケーション
            "あぼがど" to "あぼかど",        // アボカド
            "ばっく" to "ばっぐ",            // バッグ
            "べっと" to "べっど",            // ベッド
            "でぃすくとっぷ" to "ですくとっぷ", // デスクトップ
            "あたっち" to "あたっち",
            "ぼらんてぃあ" to "ぼらんてぃあ",
            "ぷれぜんてーしょん" to "ぷれぜんてーしょん",
            // === IT用語 ===
            "ぱらめーた" to "ぱらめーたー",   // パラメーター
            "ぷろぱてぃ" to "ぷろぱてぃー",   // プロパティー
            "でぃれくとり" to "でぃれくとりー", // ディレクトリー
            "れぽじとり" to "りぽじとりー",   // リポジトリー
            "ぶらうざ" to "ぶらうざー",      // ブラウザー
            "さーば" to "さーばー",          // サーバー
            "こんてな" to "こんてなー",      // コンテナー
            "どっか" to "どっかー",          // Docker
            "くばねてぃす" to "くーばねてぃす", // Kubernetes
            "ぎっとはぶ" to "ぎっとはぶ",     // GitHub
            // === 話し言葉の誤り ===
            "ゆわれた" to "いわれた",         // 言われた
            "ゆった" to "いった",            // 言った
            "ゆってた" to "いってた",         // 言ってた
            "ゆってる" to "いってる",         // 言ってる
            "ゆう" to "いう",               // 言う
            "ちがくて" to "ちがって",         // 違って
            "ちがかった" to "ちがった",        // 違った
            "やっぱし" to "やっぱり",         // やっぱり
            "やぱり" to "やっぱり",
            "すいません" to "すみません",
            "あざす" to "ありがとうございます",
            "あざっす" to "ありがとうございます",
            "おなしゃす" to "おねがいします",
            "わかんない" to "わからない",
            "しんない" to "しらない",
            "つーか" to "というか",
            "てか" to "というか",
            "じゃね" to "ではないか",
            // === 音便の誤入力 ===
            "あったかい" to "あたたかい",     // 暖かい
            "つったってる" to "つったってる",
            "おもしれー" to "おもしろい",
            "すげー" to "すごい",
            "やべー" to "やばい",
            "でけー" to "でかい",
            "はえー" to "はやい",
            "うめー" to "うまい",
            "つえー" to "つよい",
            "ねみー" to "ねむい",
            // === 二重母音・促音の誤り ===
            "おとうさん" to "おとうさん",
            "おかあさん" to "おかあさん",
            "にいさん" to "にいさん",
            "ねえさん" to "ねえさん",
            "ちっちゃい" to "ちいさい",       // 小さい
            "おっきい" to "おおきい",        // 大きい
            "ちっさい" to "ちいさい",
        )
        val corrected = corrections[kana]
        if (corrected != null && corrected != kana) {
            val entries = dict[corrected]
            if (entries != null) {
                for (entry in entries.take(3)) {
                    if (seen.add(entry.surface)) {
                        results.add(ConversionCandidate(
                            surface = entry.surface,
                            reading = corrected,
                            cost = entry.cost + 500, // Small penalty — likely what user meant
                        ))
                    }
                }
            }
            val viterbi = viterbiEngine.search(corrected)
            for (v in viterbi.take(2)) {
                if (seen.add(v.surface)) {
                    results.add(v.copy(cost = v.cost + 500))
                }
            }
        }

        // 3. Single character deletion (one extra char typed)
        if (kana.length >= 4) {
            for (i in kana.indices) {
                val deleted = kana.removeRange(i, i + 1)
                if (seen.add(deleted)) {
                    val entries = dict[deleted]
                    if (entries != null) {
                        for (entry in entries.take(1)) {
                            results.add(ConversionCandidate(
                                surface = entry.surface,
                                reading = deleted,
                                cost = entry.cost + 3000,
                            ))
                            if (results.size >= limit) return results.sortedBy { it.cost }
                        }
                    }
                }
            }
        }

        return results.sortedBy { it.cost }.take(limit)
    }

    // Delegation wrappers for user dictionary / phrase features
    fun registerUserWord(reading: String, surface: String, comment: String = "") {
        userLearner.registerUserWord(reading, surface, comment)
    }

    fun removeUserWord(reading: String, surface: String) {
        userLearner.removeUserWord(reading, surface)
    }

    fun recordPhrase(reading: String, surface: String) {
        userLearner.recordPhrase(reading, surface)
    }

    fun findPhraseCompletions(readingPrefix: String, limit: Int = 5): List<ConversionCandidate> {
        return userLearner.findPhraseCompletions(readingPrefix, limit)
    }
}
