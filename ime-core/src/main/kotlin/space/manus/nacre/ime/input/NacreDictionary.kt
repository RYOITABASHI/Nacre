package space.manus.nacre.ime.input

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import space.manus.nacre.ai.KenLmScorer
import space.manus.nacre.ime.input.DictionaryManager.Companion.DEFAULT_CONNECTION_COST
import space.manus.nacre.ime.input.DictionaryManager.Companion.isAdjective
import space.manus.nacre.ime.input.DictionaryManager.Companion.isAuxVerb
import space.manus.nacre.ime.input.DictionaryManager.Companion.isContentWord
import space.manus.nacre.ime.input.DictionaryManager.Companion.isFunctionWord
import space.manus.nacre.ime.input.DictionaryManager.Companion.isNoun
import space.manus.nacre.ime.input.DictionaryManager.Companion.isParticle
import space.manus.nacre.ime.input.DictionaryManager.Companion.isSymbol
import space.manus.nacre.ime.input.DictionaryManager.Companion.isVerb

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
    private inline val staticBigrams get() = dictManager.staticBigrams

    // User learning: boost for selected candidates (thread-safe)
    private val userBoost = ConcurrentHashMap<String, Int>(1000)

    // Bigram learning: "prevSurface→reading:surface" → count
    private val bigramBoost = ConcurrentHashMap<String, Int>(500)

    // Trigram learning: "prev2→prev1→reading:surface" → count
    private val trigramBoost = ConcurrentHashMap<String, Int>(300)

    // Recent history: ordered list of recently committed candidates (newest first)
    private val recentHistory = java.util.LinkedList<ConversionCandidate>()
    private val maxHistory = 200

    // User dictionary: reading → surface, registered by user (custom words, names, etc.)
    private val userDictionary = ConcurrentHashMap<String, MutableList<UserDictEntry>>(100)

    // Phrase memory: committed multi-word phrases for phrase completion
    // Key: first 2 chars of reading, Value: list of phrase entries
    private val phraseMemory = ConcurrentHashMap<String, MutableList<PhraseEntry>>(200)
    private val maxPhrases = 500

    // Learning epoch: increments each session, used for frequency decay
    private var learningEpoch: Int = 0

    // English word dictionary (hiragana reading → English words)
    private val englishDict = HashMap<String, MutableList<DictEntry>>(5000)

    // Full English dictionary: lowercase key → list of DictEntry (surface may have mixed case)
    private val englishFullDict = HashMap<String, MutableList<DictEntry>>(25000)
    // Sorted keys for binary-search prefix matching
    private var englishSortedKeys: Array<String> = emptyArray()
    // English word learning: "prevWord→word" → count (bigram boost)
    private val englishBigramBoost = ConcurrentHashMap<String, Int>(200)
    private var lastCommittedEnglish: String = ""

    // N-gram context: last 4 committed surfaces (for KenLM 5-gram)
    private val committedContext = ArrayDeque<String>(4)

    // Last committed right POS group (for connection cost to next word)
    private var lastRightGroup: Int = 0  // BOS/EOS

    // Convenience accessors for backward compat
    private val lastCommittedSurface: String get() = committedContext.firstOrNull() ?: ""
    private val secondLastCommittedSurface: String get() = committedContext.getOrNull(1) ?: ""

    // KenLM 5-gram language model scorer (optional, loaded from ime-ai)
    @Volatile
    var kenLmScorer: KenLmScorer? = null

    private var loaded = false
    private var savePending = false
    private var lastSaveTime = 0L

    /** Total number of dictionary entries loaded (for debug display) */
    val entryCount: Int get() = dictManager.entryCount

    fun load() {
        if (loaded) return

        // Load dictionaries, connection matrix, static bigrams via DictionaryManager
        dictManager.load()

        loadEnglishDict()
        buildRomajiEnglishIndex()
        loadEnglishFullDict()
        loadUserBoost()
        loadUserDictionary()
        loadPhraseMemory()

        // Inject user dictionary entries into the main dict for conversion
        injectUserDictionary()

        // Increment learning epoch and apply frequency decay
        learningEpoch++
        applyFrequencyDecay()
        saveEpoch()

        loaded = true

        // Wire ViterbiEngine callbacks
        viterbiEngine.boostFunction = ::applyBoost
        viterbiEngine.posContextCostFunction = ::posContextCost
    }

    // --- DictionaryProvider implementation ---

    /** Sync mutable context to ViterbiEngine before calling its methods */
    private fun syncViterbiContext() {
        viterbiEngine.lastRightGroup = lastRightGroup
        viterbiEngine.committedContext = committedContext
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
            var cost = applyBoost(c.reading, c.surface, c.cost)
            cost = posContextCost(c.reading, c.surface, cost)
            c.copy(cost = cost)
        }.toMutableList()

        kenLmRescore(boosted)

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
        val historyMatches = recentHistory.filter {
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
            var cost = applyBoost(c.reading, c.surface, c.cost)
            cost = posContextCost(c.reading, c.surface, cost)
            c.copy(cost = cost)
        }.toMutableList()

        kenLmRescore(boosted)

        return boosted.sortedBy { it.cost }.take(if (kana.length <= 3) 40 else 25)
    }

    override fun recordSelection(candidate: ConversionCandidate) {
        val key = "${candidate.reading}:${candidate.surface}"
        userBoost[key] = (userBoost[key] ?: 0) + 1

        // Bigram
        if (lastCommittedSurface.isNotEmpty()) {
            val bigramKey = "$lastCommittedSurface→$key"
            bigramBoost[bigramKey] = (bigramBoost[bigramKey] ?: 0) + 1
        }
        // Trigram
        if (secondLastCommittedSurface.isNotEmpty() && lastCommittedSurface.isNotEmpty()) {
            val trigramKey = "$secondLastCommittedSurface→$lastCommittedSurface→$key"
            trigramBoost[trigramKey] = (trigramBoost[trigramKey] ?: 0) + 1
        }
        committedContext.addFirst(candidate.surface)
        while (committedContext.size > 4) committedContext.removeLast()

        // Update POS context from the selected candidate's dictionary entry
        val entries = dict[candidate.reading]
        val matchEntry = entries?.firstOrNull { it.surface == candidate.surface }
        lastRightGroup = matchEntry?.rightGroup ?: 1

        // Recent history
        recentHistory.removeAll { it.surface == candidate.surface && it.reading == candidate.reading }
        // Store with a low cost so history candidates rank near the top.
        // The user explicitly selected this, so it should strongly influence future predictions.
        val historyCost = minOf(candidate.cost, 2000).coerceAtLeast(300)
        recentHistory.addFirst(candidate.copy(cost = historyCost))
        while (recentHistory.size > maxHistory) recentHistory.removeLast()

        debouncedSave()
    }

    fun updateContext(surface: String) {
        committedContext.addFirst(surface)
        while (committedContext.size > 4) committedContext.removeLast()
        lastRightGroup = 0 // BOS/EOS for raw text
    }

    /**
     * Predict next word based on committed context.
     * Uses trigram/bigram history, static bigrams, POS-based common followers,
     * and KenLM scoring for ranking.
     */
    fun predictNextWord(limit: Int = 8): List<ConversionCandidate> {
        if (lastCommittedSurface.isEmpty()) return emptyList()

        val results = mutableListOf<ConversionCandidate>()
        val seen = mutableSetOf<String>()

        // 1. Trigram matches (strongest signal): prev2→prev1→X
        if (secondLastCommittedSurface.isNotEmpty()) {
            val triPrefix = "$secondLastCommittedSurface→$lastCommittedSurface→"
            for ((key, count) in trigramBoost) {
                if (key.startsWith(triPrefix) && count > 0) {
                    val target = key.removePrefix(triPrefix)
                    val parts = target.split(':', limit = 2)
                    if (parts.size == 2) {
                        val surface = parts[1]
                        if (seen.add(surface)) {
                            results.add(ConversionCandidate(
                                surface = surface,
                                reading = parts[0],
                                cost = maxOf(0, 3000 - count * 1000),
                            ))
                        }
                    }
                }
            }
        }

        // 2. Bigram matches (user learned): prev1→X
        val biPrefix = "$lastCommittedSurface→"
        for ((key, count) in bigramBoost) {
            if (key.startsWith(biPrefix) && count > 0) {
                val target = key.removePrefix(biPrefix)
                val parts = target.split(':', limit = 2)
                if (parts.size == 2) {
                    val surface = parts[1]
                    if (seen.add(surface)) {
                        results.add(ConversionCandidate(
                            surface = surface,
                            reading = parts[0],
                            cost = maxOf(0, 4000 - count * 800),
                        ))
                    }
                }
            }
        }

        // 3. Static bigram matches (common collocations from bigrams.tsv)
        if (results.size < limit) {
            for ((key, boost) in staticBigrams) {
                if (key.startsWith(biPrefix)) {
                    val target = key.removePrefix(biPrefix)
                    val parts = target.split(':', limit = 2)
                    if (parts.size == 2) {
                        val surface = parts[1]
                        if (seen.add(surface)) {
                            results.add(ConversionCandidate(
                                surface = surface,
                                reading = parts[0],
                                cost = maxOf(1000, 5000 - boost),
                            ))
                        }
                    }
                    if (results.size >= limit * 2) break
                }
            }
        }

        // 4. POS-based common followers: if last word was a verb, suggest particles etc.
        if (results.size < limit) {
            val commonFollowers = getCommonFollowersForContext()
            for (follower in commonFollowers) {
                if (seen.add(follower.surface)) {
                    results.add(follower)
                    if (results.size >= limit * 2) break
                }
            }
        }

        // 5. Recent history (fallback)
        if (results.size < limit) {
            for (h in recentHistory) {
                if (seen.add(h.surface) && h.surface != lastCommittedSurface) {
                    results.add(h.copy(cost = 5000))
                    if (results.size >= limit) break
                }
            }
        }

        // 6. KenLM rescoring for all next-word predictions
        if (results.size > 1) {
            kenLmRescore(results)
        }

        return results.sortedBy { it.cost }.take(limit)
    }

    /**
     * Generate common follow-up word candidates based on POS context.
     * E.g., after a noun suggest particles (は、が、を、の、に、で、と、も、から、まで).
     * After a verb suggest auxiliary verbs and conjunctions.
     */
    private fun getCommonFollowersForContext(): List<ConversionCandidate> {
        val results = mutableListOf<ConversionCandidate>()

        // Common particles/auxiliary patterns based on last word POS
        val isLastNoun = isNoun(lastRightGroup) || lastRightGroup == 0
        val isLastVerb = isVerb(lastRightGroup)
        val isLastAdj = isAdjective(lastRightGroup)
        val isLastParticle = isParticle(lastRightGroup)

        if (isLastNoun) {
            // After noun: particles
            val particles = listOf(
                "は" to "は", "が" to "が", "を" to "を", "の" to "の",
                "に" to "に", "で" to "で", "と" to "と", "も" to "も",
                "から" to "から", "まで" to "まで", "って" to "って",
                "です" to "です", "だ" to "だ",
            )
            for ((reading, surface) in particles) {
                results.add(ConversionCandidate(surface = surface, reading = reading, cost = 4500))
            }
        } else if (isLastVerb || isLastAdj) {
            // After verb/adjective: auxiliaries, conjunctions, nominalizers
            val followers = listOf(
                "こと" to "こと", "の" to "の", "ため" to "ため",
                "ので" to "ので", "から" to "から", "けど" to "けど",
                "が" to "が", "と" to "と", "し" to "し",
                "です" to "です", "ます" to "ます",
                "た" to "た", "ない" to "ない",
                "ている" to "ている", "ていた" to "ていた",
                "てから" to "てから", "ても" to "ても",
                "ように" to "ように", "ようと" to "ようと",
                "ほう" to "方", "とき" to "時",
                "ところ" to "ところ", "ばかり" to "ばかり",
            )
            for ((reading, surface) in followers) {
                results.add(ConversionCandidate(surface = surface, reading = reading, cost = 4800))
            }
        } else if (isLastParticle) {
            // After particle: common verbs/adjectives as fallback
            val commonVerbs = listOf(
                "する" to "する", "なる" to "なる", "ある" to "ある",
                "いる" to "いる", "できる" to "できる", "思う" to "思う",
                "言う" to "言う", "見る" to "見る", "行く" to "行く",
                "来る" to "来る", "使う" to "使う", "知る" to "知る",
                "分かる" to "分かる", "考える" to "考える", "作る" to "作る",
                "いい" to "いい", "ない" to "ない", "多い" to "多い",
                "必要" to "必要", "大丈夫" to "大丈夫",
            )
            for ((reading, surface) in commonVerbs) {
                results.add(ConversionCandidate(surface = surface, reading = reading, cost = 5000))
            }
            // Also use recent history for personalized predictions
            for (h in recentHistory) {
                val entries = dict[h.reading]
                val entry = entries?.firstOrNull { it.surface == h.surface }
                if (entry != null && isContentWord(entry.leftGroup)) {
                    results.add(h.copy(cost = 4800))
                    if (results.size >= 25) break
                }
            }
        } else if (isAuxVerb(lastRightGroup)) {
            // After auxiliary verb (ます/です/た/etc.): sentence connectors
            val followers = listOf(
                "が" to "が", "けど" to "けど", "ので" to "ので",
                "から" to "から", "し" to "し", "ね" to "ね",
                "よ" to "よ", "よね" to "よね",
            )
            for ((reading, surface) in followers) {
                results.add(ConversionCandidate(surface = surface, reading = reading, cost = 4500))
            }
        }

        return results
    }

    private fun debouncedSave() {
        val now = System.currentTimeMillis()
        if (now - lastSaveTime > 5000) {
            lastSaveTime = now
            saveUserBoost()
        } else {
            savePending = true
        }
    }

    fun flushPendingSave() {
        if (savePending) {
            saveUserBoost()
            savePending = false
        }
    }

    // --- Connection cost (delegated to DictionaryManager) ---

    private fun getConnectionCost(prevRightId: Int, currLeftId: Int): Int =
        dictManager.getConnectionCost(prevRightId, currLeftId)

    // --- Boost calculation ---

    private fun applyBoost(reading: String, surface: String, baseCost: Int): Int {
        val key = "$reading:$surface"
        val unigramCount = userBoost[key] ?: 0
        val bigramCount = if (lastCommittedSurface.isNotEmpty()) {
            bigramBoost["$lastCommittedSurface→$key"] ?: 0
        } else 0
        val trigramCount = if (secondLastCommittedSurface.isNotEmpty() && lastCommittedSurface.isNotEmpty()) {
            trigramBoost["$secondLastCommittedSurface→$lastCommittedSurface→$key"] ?: 0
        } else 0

        // Static bigram boost (from bigrams.tsv — common Japanese collocations)
        val staticBoostVal = if (lastCommittedSurface.isNotEmpty()) {
            staticBigrams["$lastCommittedSurface→$key"] ?: 0
        } else 0

        // First use gets the biggest boost (diminishing returns after)
        // 1st use: 2000, 2nd: 3500, 3rd: 4500, 4th: 5200, 5th+: 5500
        val unigramBoostVal = when {
            unigramCount >= 5 -> 5500
            unigramCount >= 4 -> 5200
            unigramCount >= 3 -> 4500
            unigramCount >= 2 -> 3500
            unigramCount >= 1 -> 2000
            else -> 0
        }
        val bigramBoostVal = minOf(bigramCount, 4) * 2500
        val trigramBoostVal = minOf(trigramCount, 3) * 3500
        val totalBoost = minOf(unigramBoostVal + bigramBoostVal + trigramBoostVal + staticBoostVal, 22000)
        return maxOf(100, baseCost - totalBoost)
    }

    /**
     * POS-aware cost adjustment for a candidate given the current committed context.
     * Rewards natural POS transitions, penalizes unnatural ones.
     *
     * The connection cost matrix (Mozc /3 scaled) has these typical ranges:
     * - Natural transitions (noun→particle, verb→auxiliary): 200-800
     * - Neutral transitions: 800-1200
     * - Unnatural transitions (particle→particle, adj→noun directly): 2000-6000
     *
     * We use a neutral point of 800 and asymmetric scaling:
     * - Good transitions get a bonus (÷2 to avoid over-reliance)
     * - Bad transitions get a stronger penalty (÷1.5) to push them down
     */
    private fun posContextCost(reading: String, surface: String, baseCost: Int): Int {
        if (lastRightGroup == 0) return baseCost
        val entries = dict[reading]
        val entry = entries?.firstOrNull { it.surface == surface } ?: return baseCost
        val connCost = getConnectionCost(lastRightGroup, entry.leftGroup)
        val delta = connCost - 800
        return if (delta <= 0) {
            // Good transition: mild bonus
            baseCost + delta / 2
        } else {
            // Bad transition: stronger penalty
            baseCost + (delta * 2) / 3
        }
    }

    /**
     * Rescore candidates using KenLM 5-gram language model.
     * Candidates with segments get LM score blended into their cost.
     * Candidates without segments are scored using surface as a single word.
     */
    private fun kenLmRescore(candidates: MutableList<ConversionCandidate>) {
        val scorer = kenLmScorer ?: return
        if (!scorer.isReady() || candidates.isEmpty()) return
        if (candidates.size <= 2) return
        val maxScore = 40.coerceAtMost(candidates.size)

        // Use up to 4 words of context for KenLM 5-gram
        val precedingContext = committedContext.reversed().joinToString(" ")

        val segmentLists = candidates.take(maxScore).map { c ->
            c.segments.ifEmpty { listOf(c.surface) }
        }

        val scores = scorer.scoreBatchNormalized(segmentLists, precedingContext)

        // Dynamic KenLM weight: longer input = more context = higher trust in LM
        // Short (1-3 chars): weight 1800 — dict cost is more reliable
        // Medium (4-7 chars): weight 2500 — balanced
        // Long (8+ chars): weight 3200 — LM context is crucial
        val totalChars = candidates.firstOrNull()?.reading?.length ?: 4
        val hasContext = committedContext.isNotEmpty()
        val dynamicWeight = when {
            // With committed context, even short inputs benefit from LM
            totalChars <= 3 -> if (hasContext) 2200f else 1800f
            totalChars <= 7 -> 2500f
            else -> 3200f
        }

        // Increase weight when we have committed context (cross-sentence scoring)
        // More context words = higher confidence in LM
        val contextMultiplier = when {
            committedContext.size >= 3 -> 1.25f
            committedContext.size >= 2 -> 1.20f
            hasContext -> 1.15f
            else -> 1.0f
        }
        val contextWeight = dynamicWeight * contextMultiplier

        for (i in 0 until maxScore) {
            if (i >= scores.size) break
            // scores[i] is log10 prob (negative; higher = better)
            // Use sqrt normalization to mildly favor longer sequences without over-penalizing
            val wordCount = segmentLists[i].size.coerceAtLeast(1)
            val lmBonus = (scores[i] * -contextWeight / kotlin.math.sqrt(wordCount.toFloat())).toInt()
            candidates[i] = candidates[i].copy(cost = candidates[i].cost + lmBonus)
        }
    }

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
                    var cost = applyBoost(reading, entry.surface, entry.cost)
                    cost = posContextCost(reading, entry.surface, cost)
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

    // --- User learning persistence ---

    private fun loadUserBoost() {
        try {
            val prefs = context.getSharedPreferences("nacre_user_dict", Context.MODE_PRIVATE)

            val data = prefs.getString("boost", null)
            if (data != null) {
                for (line in data.split('\n')) {
                    val parts = line.split('\t')
                    if (parts.size == 2) {
                        userBoost[parts[0]] = parts[1].toIntOrNull() ?: 0
                    }
                }
            }

            val bigramData = prefs.getString("bigram", null)
            if (bigramData != null) {
                for (line in bigramData.split('\n')) {
                    val parts = line.split('\t')
                    if (parts.size == 2) {
                        bigramBoost[parts[0]] = parts[1].toIntOrNull() ?: 0
                    }
                }
            }

            val trigramData = prefs.getString("trigram", null)
            if (trigramData != null) {
                for (line in trigramData.split('\n')) {
                    val parts = line.split('\t')
                    if (parts.size == 2) {
                        trigramBoost[parts[0]] = parts[1].toIntOrNull() ?: 0
                    }
                }
            }

            val historyData = prefs.getString("history", null)
            if (historyData != null) {
                for (line in historyData.split('\n')) {
                    if (line.isBlank()) continue
                    val parts = line.split('\t')
                    if (parts.size >= 2) {
                        recentHistory.add(ConversionCandidate(
                            surface = parts[0],
                            reading = parts[1],
                            cost = 0,
                        ))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("NacreDictionary", "Failed to load user boost", e)
        }
    }

    private fun saveUserBoost() {
        try {
            val prefs = context.getSharedPreferences("nacre_user_dict", Context.MODE_PRIVATE)

            val data = userBoost.entries
                .sortedByDescending { it.value }
                .take(5000)
                .joinToString("\n") { "${it.key}\t${it.value}" }

            val bigramData = bigramBoost.entries
                .sortedByDescending { it.value }
                .take(3000)
                .joinToString("\n") { "${it.key}\t${it.value}" }

            val trigramData = trigramBoost.entries
                .sortedByDescending { it.value }
                .take(2000)
                .joinToString("\n") { "${it.key}\t${it.value}" }

            val historyData = recentHistory
                .take(maxHistory)
                .joinToString("\n") { "${it.surface}\t${it.reading}" }

            prefs.edit()
                .putString("boost", data)
                .putString("bigram", bigramData)
                .putString("trigram", trigramData)
                .putString("history", historyData)
                .apply()
        } catch (e: Exception) {
            Log.w("NacreDictionary", "Failed to save user boost", e)
        }
    }

    // --- User dictionary persistence ---

    private fun loadUserDictionary() {
        try {
            val prefs = context.getSharedPreferences("nacre_user_dict", Context.MODE_PRIVATE)
            val data = prefs.getString("user_dictionary", null) ?: return
            for (line in data.split('\n')) {
                if (line.isBlank()) continue
                val parts = line.split('\t')
                if (parts.size >= 2) {
                    val reading = parts[0]
                    val surface = parts[1]
                    val comment = if (parts.size >= 3) parts[2] else ""
                    userDictionary.getOrPut(reading) { mutableListOf() }
                        .add(UserDictEntry(reading, surface, comment))
                }
            }
            Log.i("NacreDictionary", "User dictionary loaded: ${userDictionary.values.sumOf { it.size }} entries")
        } catch (e: Exception) {
            Log.w("NacreDictionary", "Failed to load user dictionary", e)
        }
    }

    private fun saveUserDictionary() {
        try {
            val prefs = context.getSharedPreferences("nacre_user_dict", Context.MODE_PRIVATE)
            val data = userDictionary.values.flatten()
                .joinToString("\n") { "${it.reading}\t${it.surface}\t${it.comment}" }
            prefs.edit().putString("user_dictionary", data).apply()
        } catch (e: Exception) {
            Log.w("NacreDictionary", "Failed to save user dictionary", e)
        }
    }

    /**
     * Inject user dictionary entries into the main dict so they appear in conversion candidates.
     * User entries get a high priority (low cost).
     */
    private fun injectUserDictionary() {
        for ((reading, entries) in userDictionary) {
            val existing = dict.getOrPut(reading) { mutableListOf() }
            for (entry in entries) {
                if (existing.none { it.surface == entry.surface }) {
                    existing.add(DictEntry(
                        surface = entry.surface,
                        cost = 500, // High priority — user registered words
                        leftGroup = 0,
                        rightGroup = 0,
                    ))
                }
            }
        }
    }

    /**
     * Register a word in the user dictionary.
     * @param reading hiragana reading
     * @param surface the kanji/text surface form
     * @param comment optional memo
     */
    fun registerUserWord(reading: String, surface: String, comment: String = "") {
        val entries = userDictionary.getOrPut(reading) { mutableListOf() }
        if (entries.none { it.surface == surface }) {
            entries.add(UserDictEntry(reading, surface, comment))
            // Also inject into the live dict
            val existing = dict.getOrPut(reading) { mutableListOf() }
            if (existing.none { it.surface == surface }) {
                existing.add(DictEntry(surface = surface, cost = 500, leftGroup = 0, rightGroup = 0))
            }
            saveUserDictionary()
        }
    }

    /**
     * Remove a word from the user dictionary.
     */
    fun removeUserWord(reading: String, surface: String) {
        userDictionary[reading]?.removeAll { it.surface == surface }
        if (userDictionary[reading]?.isEmpty() == true) userDictionary.remove(reading)
        dict[reading]?.removeAll { it.surface == surface && it.cost == 500 }
        saveUserDictionary()
    }

    // --- Phrase memory persistence ---

    private fun loadPhraseMemory() {
        try {
            val prefs = context.getSharedPreferences("nacre_user_dict", Context.MODE_PRIVATE)
            val data = prefs.getString("phrase_memory", null) ?: return
            for (line in data.split('\n')) {
                if (line.isBlank()) continue
                val parts = line.split('\t')
                if (parts.size >= 3) {
                    val reading = parts[0]
                    val surface = parts[1]
                    val count = parts[2].toIntOrNull() ?: 1
                    val epoch = if (parts.size >= 4) parts[3].toIntOrNull() ?: 0 else 0
                    val key = reading.take(2)
                    phraseMemory.getOrPut(key) { mutableListOf() }
                        .add(PhraseEntry(reading, surface, count, epoch))
                }
            }
            Log.i("NacreDictionary", "Phrase memory loaded: ${phraseMemory.values.sumOf { it.size }} phrases")
        } catch (e: Exception) {
            Log.w("NacreDictionary", "Failed to load phrase memory", e)
        }
    }

    private fun savePhraseMemory() {
        try {
            val prefs = context.getSharedPreferences("nacre_user_dict", Context.MODE_PRIVATE)
            val data = phraseMemory.values.flatten()
                .sortedByDescending { it.count }
                .take(maxPhrases)
                .joinToString("\n") { "${it.reading}\t${it.surface}\t${it.count}\t${it.lastEpoch}" }
            prefs.edit().putString("phrase_memory", data).apply()
        } catch (e: Exception) {
            Log.w("NacreDictionary", "Failed to save phrase memory", e)
        }
    }

    /**
     * Record a committed phrase for phrase completion.
     * Only stores phrases of 4+ characters (shorter ones are handled by normal learning).
     */
    fun recordPhrase(reading: String, surface: String) {
        if (surface.length < 4) return
        val key = reading.take(2)
        val entries = phraseMemory.getOrPut(key) { mutableListOf() }
        val existing = entries.find { it.surface == surface }
        if (existing != null) {
            existing.count++
            existing.lastEpoch = learningEpoch
        } else {
            entries.add(PhraseEntry(reading, surface, 1, learningEpoch))
            // Evict oldest if over limit
            if (entries.size > 50) {
                entries.sortByDescending { it.count }
                while (entries.size > 40) entries.removeAt(entries.lastIndex)
            }
        }
        savePhraseMemory()
    }

    /**
     * Look up phrase completions for a partial reading.
     * Returns phrases whose reading starts with the given prefix.
     */
    fun findPhraseCompletions(readingPrefix: String, limit: Int = 5): List<ConversionCandidate> {
        if (readingPrefix.length < 2) return emptyList()
        val key = readingPrefix.take(2)
        val entries = phraseMemory[key] ?: return emptyList()
        return entries
            .filter { it.reading.startsWith(readingPrefix) && it.reading.length > readingPrefix.length }
            .sortedByDescending { it.count }
            .take(limit)
            .map { ConversionCandidate(surface = it.surface, reading = it.reading, cost = 100) }
    }

    // --- Frequency decay ---

    /**
     * Apply frequency decay to user boost scores.
     * Scores decay by 10% each epoch (session), preventing stale entries from dominating.
     */
    private fun applyFrequencyDecay() {
        val decayFactor = 0.9f
        val iterator = userBoost.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val decayed = (entry.value * decayFactor).toInt()
            if (decayed <= 0) {
                iterator.remove()
            } else {
                entry.setValue(decayed)
            }
        }
        // Also decay bigram/trigram boosts
        for (map in listOf(bigramBoost, trigramBoost)) {
            val iter = map.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next()
                val decayed = (entry.value * decayFactor).toInt()
                if (decayed <= 0) iter.remove()
                else entry.setValue(decayed)
            }
        }
    }

    private fun saveEpoch() {
        try {
            val prefs = context.getSharedPreferences("nacre_user_dict", Context.MODE_PRIVATE)
            learningEpoch = prefs.getInt("learning_epoch", 0) + 1
            prefs.edit().putInt("learning_epoch", learningEpoch).apply()
        } catch (e: Exception) {
            Log.w("NacreDictionary", "Failed to save epoch", e)
        }
    }
}
