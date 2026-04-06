package space.manus.nacre.ime.input

import space.manus.nacre.ai.KenLmScorer
import space.manus.nacre.ime.input.DictionaryManager.Companion.isContentWord
import space.manus.nacre.ime.input.DictionaryManager.Companion.isFunctionWord
import space.manus.nacre.ime.input.DictionaryManager.Companion.isParticle
import space.manus.nacre.ime.input.DictionaryManager.Companion.isAuxVerb

/**
 * Viterbi beam-search conversion engine with KenLM integration.
 *
 * Extracted from ConversionPipeline — owns the core segmentation algorithm,
 * kana variant generation, katakana conversion helpers, and exact-match lookup.
 *
 * Does NOT own user learning (boost) or POS-context ranking; those are
 * injected via callback functions so the engine remains stateless w.r.t. learning.
 */
class ViterbiEngine(
    private val dictManager: DictionaryManager,
    private val kenLmScorerProvider: () -> KenLmScorer? = { null },
) {
    companion object {
        // KenLM weight for post-hoc rescoring (high: LM should strongly influence final ranking)
        const val KENLM_WEIGHT = 5000f
        // KenLM weight inside Viterbi (moderate: guide segmentation without over-pruning beam)
        const val VITERBI_LM_WEIGHT = 3000f

        /**
         * Calculate dynamic beam width based on input length, ambiguity, and LM availability.
         * @param inputLength number of kana characters
         * @param ambiguity ratio of dictionary entries with multiple readings (0.0-1.0)
         * @param hasLM whether a language model is available
         * @return beam width K
         */
        fun dynamicBeamWidth(inputLength: Int, ambiguity: Float = 0.5f, hasLM: Boolean = false): Int {
            // Base beam: shorter input needs wider beam (more ambiguity per char)
            val base = when {
                inputLength <= 4 -> 40
                inputLength <= 6 -> 35
                inputLength <= 10 -> 30
                inputLength <= 15 -> 25
                else -> 20
            }
            // Ambiguity multiplier: high ambiguity (many homophone readings) = wider beam
            val ambiguityMult = 1.0f + ambiguity * 0.5f  // 1.0x to 1.5x
            val adjusted = (base * ambiguityMult).toInt()
            // LM bonus: language model can better discriminate, allow more candidates
            val lmBonus = if (hasLM) 10 else 0
            // Cap: never exceed 60 (memory/time), never below 15 (quality)
            return (adjusted + lmBonus).coerceIn(15, 60)
        }
    }

    // These are set by the caller (ConversionPipeline) to provide context
    var lastRightGroup: Int = 0
    var committedContext: ArrayDeque<String> = ArrayDeque(4)

    // Callback for boost application — ViterbiEngine doesn't own user learning
    var boostFunction: ((String, String, Int) -> Int)? = null

    // Callback for POS context cost — ViterbiEngine doesn't own candidate ranking
    var posContextCostFunction: ((String, String, Int) -> Int)? = null

    // Convenience: dict access
    private inline val dict get() = dictManager.dict

    /**
     * Core Viterbi beam-search conversion.
     * Finds the best segmentation of [kana] into dictionary words,
     * using POS connection costs and optional KenLM language model scoring.
     */
    fun search(kana: String): List<ConversionCandidate> {
        val n = kana.length
        if (n == 0) return emptyList()

        // Check if KenLM incremental scoring is available
        val scorer = kenLmScorerProvider()
        val lmAvailable = scorer != null && scorer.isReady() && scorer.getStateSize() > 0
        val lmBosState = if (lmAvailable) scorer!!.getBeginState() else null
        // Build preceding context state by feeding committed words through the LM
        val lmInitState: ByteArray? = if (lmAvailable && lmBosState != null && committedContext.isNotEmpty()) {
            var state: ByteArray = lmBosState
            for (word in committedContext.reversed()) {
                val result = scorer!!.scoreWordIncremental(state, word)
                state = result?.second ?: state
            }
            state
        } else {
            lmBosState
        }

        data class Node(
            val cost: Int,
            val backPos: Int,
            val surface: String,
            val reading: String,
            val segCount: Int,
            val rightGroup: Int,
            val prevNode: Node?,
            val lmState: ByteArray?,   // KenLM state for incremental scoring
            val lmScore: Float,        // Cumulative LM log10 prob
        )

        fun reconstructSegments(node: Node): List<String> {
            val segments = mutableListOf<String>()
            var cur: Node? = node
            while (cur != null && cur.surface.isNotEmpty()) {
                segments.add(cur.surface)
                cur = cur.prevNode
            }
            segments.reverse()
            return segments
        }

        // Beam width: dynamic based on input length, ambiguity, and LM availability
        val hasLm = kenLmScorerProvider()?.isReady() == true
        val ambiguity = estimateAmbiguity(kana)
        val K = dynamicBeamWidth(n, ambiguity, hasLm)
        val dp = Array(n + 1) { mutableListOf<Node>() }
        dp[0].add(Node(cost = 0, backPos = -1, surface = "", reading = "", segCount = 0,
            rightGroup = lastRightGroup, prevNode = null, lmState = lmInitState, lmScore = 0f))

        val startTimeNanos = System.nanoTime()
        val timeBudgetNanos = 50_000_000L // 50ms default

        for (endPos in 1..n) {
            // Time budget check: if we've exceeded the budget and have at least some results, break
            if (endPos > 3 && System.nanoTime() - startTimeNanos > timeBudgetNanos) {
                // Partial result: reconstruct from what we have so far
                break
            }
            val allCandidates = mutableListOf<Node>()
            val maxSegLen = minOf(endPos, if (n <= 10) 12 else 10)

            for (segLen in 1..maxSegLen) {
                val startPos = endPos - segLen
                val segment = kana.substring(startPos, endPos)

                val entries = dict[segment]
                if (entries != null && entries.isNotEmpty()) {
                    for (prevNode in dp[startPos]) {
                        val prevRG = prevNode.rightGroup
                        val isAfterContentWord = isContentWord(prevRG)
                        val isAfterFunctionWord = isFunctionWord(prevRG)

                        // Limit candidates per segment: fewer for long inputs
                        val takeN = if (n > 12) minOf(if (segLen >= 3) 12 else 8, entries.size)
                                    else if (segLen >= 3) 16 else 10
                        for (entry in entries.take(takeN)) {
                            var cost = applyBoost(segment, entry.surface, entry.cost)
                            val connCost = if (startPos == 0 && prevRG == 0) {
                                dictManager.getConnectionCost(0, entry.leftGroup)
                            } else {
                                dictManager.getConnectionCost(prevRG, entry.leftGroup)
                            }
                            cost += connCost

                            // Hiragana function word handling:
                            // When the segment is hiragana input, strongly prefer function word
                            // readings over kanji content-word readings.
                            // e.g. になっ → になっ (function) over 担っ (content)
                            val isHiraganaSegment = segment.all { it in '\u3040'..'\u309F' }
                            if (isHiraganaSegment) {
                                if (entry.surface == segment) {
                                    // Hiragana-as-is: strong bonus for function words
                                    if (isParticle(entry.leftGroup) && segLen <= 2 && isAfterContentWord) cost -= 2500
                                    if (isAuxVerb(entry.leftGroup) && segLen <= 3 && isAfterContentWord) cost -= 2000
                                    if (isFunctionWord(entry.leftGroup) && segLen <= 4) cost -= 1500
                                    if (segLen <= 3 && isAfterContentWord && !isFunctionWord(entry.leftGroup)) cost -= 800
                                    if (segLen <= 2 && isAfterFunctionWord && isFunctionWord(entry.leftGroup)) cost -= 800
                                    if (segLen <= 2 && isAfterFunctionWord && !isFunctionWord(entry.leftGroup)) cost -= 300
                                } else if (segLen <= 3 && !isContentWord(entry.leftGroup)) {
                                    // Short hiragana → kanji non-content: mild bonus
                                    cost -= 300
                                } else if (segLen <= 3 && isContentWord(entry.leftGroup)) {
                                    // Short hiragana → kanji content word: penalty
                                    // Prevents になっ→担っ, にな→仁菜 etc.
                                    cost += 2000
                                }
                            }

                            val lBonus = lengthBonus(segLen)

                            // KenLM incremental scoring within Viterbi
                            var lmCost = 0
                            var newLmState = prevNode.lmState
                            var newLmScore = prevNode.lmScore
                            if (lmAvailable && prevNode.lmState != null) {
                                val lmResult = scorer!!.scoreWordIncremental(prevNode.lmState!!, entry.surface)
                                if (lmResult != null) {
                                    newLmScore = prevNode.lmScore + lmResult.first
                                    newLmState = lmResult.second
                                    // Convert LM score to cost: negative log prob * weight
                                    // lmResult.first is log10(P(word|context)), typically -0.5 to -4.0
                                    lmCost = (lmResult.first * -VITERBI_LM_WEIGHT).toInt()
                                }
                            }

                            val totalCost = prevNode.cost + cost + lBonus + lmCost

                            allCandidates.add(Node(
                                cost = totalCost,
                                backPos = startPos,
                                surface = entry.surface,
                                reading = segment,
                                segCount = prevNode.segCount + 1,
                                rightGroup = entry.rightGroup,
                                prevNode = prevNode,
                                lmState = newLmState,
                                lmScore = newLmScore,
                            ))
                        }
                    }
                } else if (segLen == 1) {
                    for (prevNode in dp[startPos]) {
                        val connCost = if (startPos == 0) 0 else dictManager.getConnectionCost(prevNode.rightGroup, 1)
                        val totalCost = prevNode.cost + 15000 + connCost
                        allCandidates.add(Node(
                            cost = totalCost,
                            backPos = startPos,
                            surface = segment,
                            reading = segment,
                            segCount = prevNode.segCount + 1,
                            rightGroup = 1,
                            prevNode = prevNode,
                            lmState = prevNode.lmState,
                            lmScore = prevNode.lmScore,
                        ))
                    }
                }
            }

            // Keep top-K with diversity — use partial sort for performance
            allCandidates.sortBy { it.cost }
            val kept = mutableListOf<Node>()
            val seenHashes = mutableSetOf<Long>()
            for (node in allCandidates) {
                val pathHash = (System.identityHashCode(node.prevNode).toLong() shl 32) xor
                    node.surface.hashCode().toLong()
                if (seenHashes.add(pathHash)) {
                    kept.add(node)
                }
                if (kept.size >= K * 3) break
            }
            dp[endPos] = kept
        }

        val results = mutableListOf<ConversionCandidate>()
        val seen = mutableSetOf<String>()

        for (node in dp[n]) {
            val segments = reconstructSegments(node)
            val combined = segments.joinToString("")
            if (combined.isNotEmpty() && seen.add(combined)) {
                val eosCost = dictManager.getConnectionCost(node.rightGroup, 0)
                val segPenalty = if (node.segCount >= 5) (node.segCount - 4) * 500 else 0
                val finalCost = node.cost + eosCost / 2 + segPenalty
                results.add(ConversionCandidate(surface = combined, reading = kana, cost = finalCost, segments = segments))
            }
            if (results.size >= 25) break
        }

        val exactMatches = exactMatch(kana)
        if (results.isEmpty()) return exactMatches

        for (em in exactMatches) {
            if (seen.add(em.surface)) results.add(em)
        }

        val katakana = hiraganaToKatakana(kana)
        if (katakana != kana && seen.add(katakana)) {
            val maxCost = results.maxOfOrNull { it.cost } ?: 5000
            results.add(ConversionCandidate(surface = katakana, reading = kana, cost = maxCost + 500))
        }
        if (seen.add(kana)) {
            val maxCost = results.maxOfOrNull { it.cost } ?: 5000
            results.add(ConversionCandidate(surface = kana, reading = kana, cost = maxCost + 200))
        }

        return results.take(25)
    }

    /**
     * Exact dictionary match for a kana reading.
     * Returns single-word candidates with exact-match bonus applied.
     */
    fun exactMatch(kana: String): List<ConversionCandidate> {
        val entries = dict[kana] ?: return emptyList()
        // Exact match bonus: single-word results should beat multi-word Viterbi splits
        // Longer exact matches get bigger bonus (e.g. こんにちは should beat 今日+葉)
        val exactBonus = when {
            kana.length >= 8 -> -7000
            kana.length >= 7 -> -6000
            kana.length >= 5 -> -5000
            kana.length >= 4 -> -3500
            kana.length >= 3 -> -2000
            else -> -1000
        }
        return entries
            .map { entry ->
                var cost = applyBoost(kana, entry.surface, entry.cost) + exactBonus
                cost = applyPosContextCost(kana, entry.surface, cost)
                // Suppress function words (助詞・助動詞) appearing as sole candidates for 2+ char input
                if (kana.length >= 2 && isFunctionWord(entry.leftGroup) && entry.surface.length <= 1) {
                    cost += 2000
                }
                ConversionCandidate(
                    surface = entry.surface,
                    reading = kana,
                    cost = cost,
                    segments = listOf(entry.surface),
                )
            }
            .sortedBy { it.cost }
            .take(if (kana.length <= 3) 40 else 20)  // Short readings: show all kanji candidates
    }

    /**
     * Generate alternative kana readings for conversion.
     * Handles cases where standard romaji mapping produces one kana but
     * the user may intend another pronunciation.
     *
     * Examples:
     * - ちぇをん → ちぇうぉん (を→うぉ for loanwords like チェウォン)
     * - を → お (を as vowel 'o' in compound words)
     * - ぢ → じ, づ → ず (四つ仮名の読み替え)
     * - ゐ → い, ゑ → え (historical kana)
     */
    fun generateKanaVariants(kana: String): List<String> {
        val variants = mutableSetOf<String>()

        // を → うぉ replacement (loanword pronunciation)
        if ('を' in kana) {
            variants.add(kana.replace("を", "うぉ"))
            // Also try を → お (common in compound words)
            variants.add(kana.replace("を", "お"))
        }

        // ぢ ↔ じ (四つ仮名)
        if ('ぢ' in kana) {
            variants.add(kana.replace('ぢ', 'じ'))
        }
        if ('じ' in kana && kana.length <= 8) {
            variants.add(kana.replace('じ', 'ぢ'))
        }

        // づ ↔ ず
        if ('づ' in kana) {
            variants.add(kana.replace('づ', 'ず'))
        }
        if ('ず' in kana && kana.length <= 8) {
            variants.add(kana.replace('ず', 'づ'))
        }

        // ゐ → い, ゑ → え (historical)
        if ('ゐ' in kana) variants.add(kana.replace('ゐ', 'い'))
        if ('ゑ' in kana) variants.add(kana.replace('ゑ', 'え'))

        // Remove the original kana from variants
        variants.remove(kana)
        return variants.toList().take(4)  // Limit to avoid explosion
    }

    /** Convert hiragana to katakana */
    fun hiraganaToKatakana(hiragana: String): String {
        val sb = StringBuilder(hiragana.length)
        for (ch in hiragana) {
            sb.append(if (ch in '\u3041'..'\u3096') (ch + 0x60) else ch)
        }
        return sb.toString()
    }

    /** Convert hiragana to half-width katakana (半角カタカナ) */
    fun toHalfWidthKatakana(hiragana: String): String {
        val sb = StringBuilder(hiragana.length * 2) // dakuten can expand
        for (ch in hiragana) {
            val kata = if (ch in '\u3041'..'\u3096') (ch + 0x60) else ch
            val hw = DictionaryManager.HALF_WIDTH_KATAKANA_MAP[kata]
            if (hw != null) {
                sb.append(hw)
            } else {
                sb.append(kata)
            }
        }
        return sb.toString()
    }

    /**
     * Estimate appropriate cost for katakana conversion.
     * If most dictionary entries for this reading have katakana surfaces (= loanword),
     * rank katakana much higher.
     */
    fun estimateKatakanaCost(kana: String, existingResults: List<ConversionCandidate>): Int {
        // 1. Dictionary-based: if 50%+ entries are katakana, it's a loanword
        val entries = dict[kana]
        if (entries != null && entries.size >= 2) {
            val katakanaCount = entries.count { surface ->
                surface.surface.all { it in '\u30A0'..'\u30FF' || it == 'ー' }
            }
            val ratio = katakanaCount.toFloat() / entries.size
            if (ratio >= 0.5f) {
                val minCost = existingResults.minOfOrNull { it.cost } ?: 5000
                return minCost + 200
            }
        }

        // 2. Pattern-based: detect loanword-like readings even without dict entries
        // Loanword indicators: long vowel patterns, ティ/ディ/ファ/フィ etc., 4+ chars with no kanji match
        if (isLikelyLoanword(kana)) {
            val minCost = existingResults.minOfOrNull { it.cost } ?: 5000
            return minCost + 500
        }

        // Default: katakana at the bottom
        val maxCost = existingResults.maxOfOrNull { it.cost } ?: 5000
        return maxCost + 500
    }

    /**
     * Heuristic: detect if a kana reading is likely a loanword (foreign origin).
     * These readings should have katakana ranked higher.
     */
    fun isLikelyLoanword(kana: String): Boolean {
        if (kana.length < 4) return false
        // Foreign-origin syllable patterns (rarely appear in native Japanese)
        val loanwordSyllables = listOf(
            "てぃ", "でぃ", "ふぁ", "ふぃ", "ふぇ", "ふぉ",
            "うぃ", "うぇ", "うぉ", "しぇ", "じぇ", "ちぇ",
            "つぁ", "つぃ", "つぇ", "つぉ", "ゔぁ", "ゔぃ", "ゔ",
        )
        for (syllable in loanwordSyllables) {
            if (kana.contains(syllable)) return true
        }
        // Readings ending with common loanword suffixes
        val loanwordSuffixes = listOf(
            "しょん", "にんぐ", "めんと", "ねす",
            "りてぃ", "ぶる", "とりー", "ありー",
            "てぃぶ", "なる", "いず", "いずむ",
            "ーしょん", "ーにんぐ", "ーめんと",
            "ーじ", "ーる", "ーと", "ーす", "ーど", "ーぷ",
            "ふぃっく", "ろじー", "ぐらふぃ",
        )
        for (suffix in loanwordSuffixes) {
            if (kana.endsWith(suffix) && kana.length >= suffix.length + 2) return true
        }
        // No dict entries at all for 5+ char reading → likely loanword
        if (kana.length >= 5 && dict[kana] == null) return true
        return false
    }

    // --- Private helpers ---

    private fun estimateAmbiguity(kana: String): Float {
        // Sample a few substrings to estimate how ambiguous this input is
        var multiEntryCount = 0
        var totalChecked = 0
        for (len in 1..minOf(3, kana.length)) {
            for (start in 0..kana.length - len) {
                val sub = kana.substring(start, start + len)
                val entries = dict[sub]
                if (entries != null) {
                    totalChecked++
                    if (entries.size > 3) multiEntryCount++
                }
            }
        }
        return if (totalChecked == 0) 0.5f else multiEntryCount.toFloat() / totalChecked
    }

    private fun lengthBonus(segLen: Int): Int {
        // Strongly prefer longer segments — key for matching Google IME quality.
        // Longer segments = fewer word boundaries = less ambiguity.
        return when {
            segLen >= 10 -> -4000
            segLen >= 8 -> -3200
            segLen >= 7 -> -2600
            segLen >= 6 -> -2000
            segLen >= 5 -> -1500
            segLen >= 4 -> -1000
            segLen >= 3 -> -400
            segLen == 2 -> 0
            else -> 1200  // Single-char segments heavily penalized (particles handled by connection cost)
        }
    }

    /** Apply boost via callback (user learning). Returns cost unchanged if no callback set. */
    private fun applyBoost(reading: String, surface: String, cost: Int): Int {
        return boostFunction?.invoke(reading, surface, cost) ?: cost
    }

    /** Apply POS context cost via callback. Returns cost unchanged if no callback set. */
    private fun applyPosContextCost(reading: String, surface: String, cost: Int): Int {
        return posContextCostFunction?.invoke(reading, surface, cost) ?: cost
    }
}
