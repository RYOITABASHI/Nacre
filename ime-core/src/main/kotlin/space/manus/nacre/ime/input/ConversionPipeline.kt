package space.manus.nacre.ime.input

import android.content.Context
import space.manus.nacre.ai.KenLmScorer
import space.manus.nacre.ime.input.DictionaryManager.Companion.isContentWord

/**
 * ConversionPipeline — the new public API for Japanese input conversion.
 *
 * Orchestrates all extracted components:
 *   DictionaryManager, ViterbiEngine, UserLearner, CandidateRanker, EnglishMatcher
 *
 * Implements DictionaryProvider so it can be used as a drop-in replacement
 * for NacreDictionary throughout the codebase.
 */
class ConversionPipeline(private val context: Context) : DictionaryProvider {

    val dictManager = DictionaryManager(context)
    val viterbiEngine = ViterbiEngine(dictManager) { kenLmScorer }
    val userLearner = UserLearner(context, dictManager)
    val candidateRanker = CandidateRanker({ kenLmScorer }, dictManager, userLearner)
    val englishMatcher = EnglishMatcher(context)

    @Volatile
    var kenLmScorer: KenLmScorer? = null

    private var loaded = false

    /** Total number of dictionary entries loaded (for debug display) */
    val entryCount: Int get() = dictManager.entryCount

    // Convenience accessors for dictManager fields
    private inline val dict get() = dictManager.dict
    private inline val sortedReadings get() = dictManager.sortedReadings

    fun load() {
        if (loaded) return

        // Load dictionaries, connection matrix, static bigrams via DictionaryManager
        dictManager.load()

        englishMatcher.load()

        // Load user learning data (boost, user dict, phrase memory, decay)
        userLearner.load()

        loaded = true

        // Wire ViterbiEngine callbacks
        viterbiEngine.boostFunction = userLearner::applyBoost
        viterbiEngine.posContextCostFunction = candidateRanker::posContextCost

        // Wire UserLearner's rescore callback to CandidateRanker
        userLearner.rescoreFunction = candidateRanker::kenLmRescore
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

        // Rank: boost + POS context + KenLM rescore + filter + sort
        return candidateRanker.rank(results, kana)
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
            addUnique(englishMatcher.match(kana, limit = 5))
        }

        // 6. Romaji-based English candidates (e.g. "goo" → "Google")
        if (romaji.isNotEmpty() && romaji.length >= 2) {
            addUnique(englishMatcher.romajiMatch(romaji, limit = 5))
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

        // Rank: boost + POS context + KenLM rescore + filter + sort
        return candidateRanker.rank(results, kana, limit = if (kana.length <= 3) 40 else 25)
    }

    override fun recordSelection(candidate: ConversionCandidate) {
        userLearner.recordSelection(candidate)
    }

    override fun updateContext(kana: String) {
        userLearner.updateContext(kana)
    }

    override fun predictNextWord(limit: Int): List<ConversionCandidate> {
        return userLearner.predictNextWord(limit)
    }

    override fun predictEnglish(prefix: String, limit: Int): List<ConversionCandidate> =
        englishMatcher.predict(prefix, limit)

    override fun recordEnglishSelection(word: String) =
        englishMatcher.recordSelection(word)

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
                    cost = candidateRanker.posContextCost(reading, entry.surface, cost)
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

    // --- Delegation wrappers for user dictionary / phrase features ---

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
