package space.manus.nacre.ime.input

import space.manus.nacre.ai.KenLmScorer

/**
 * Candidate ranking pipeline: boost, POS context, KenLM rescore, filter, sort.
 *
 * Extracted from UserLearner (kenLmRescore, posContextCost) and
 * ConversionPipeline (inline ranking in convert/predict).
 *
 * Encapsulates the full ranking pipeline so callers just call rank().
 */
class CandidateRanker(
    private val kenLmScorerProvider: () -> KenLmScorer? = { null },
    private val dictManager: DictionaryManager,
    private val userLearner: UserLearner,
) {
    // Weight configuration (will be expanded in Task 11)
    var viterbiLmWeight = 3000f
    var rescoreWeight = 2500f
    var contextMultiplier = 1.15f

    /**
     * Full ranking pipeline: boost + POS context + KenLM rescore + filter + sort.
     *
     * @param candidates raw candidates from Viterbi/exact/prefix matching
     * @param reading the kana reading that produced these candidates
     * @param limit max number of results to return (default depends on reading length)
     * @return ranked and filtered candidates
     */
    fun rank(
        candidates: List<ConversionCandidate>,
        reading: String,
        limit: Int = if (reading.length <= 3) 40 else 30,
    ): List<ConversionCandidate> {
        if (candidates.isEmpty()) return emptyList()

        // 1. Apply user boost + 2. Apply POS context cost
        val boosted = candidates.map { c ->
            var cost = userLearner.applyBoost(c.reading, c.surface, c.cost)
            cost = posContextCost(c.reading, c.surface, cost)
            c.copy(cost = cost)
        }.toMutableList()

        // 3. KenLM rescore
        kenLmRescore(boosted)

        // 4. Post-rescore filter: remove candidates with catastrophically bad LM scores
        if (kenLmScorerProvider()?.isReady() == true && boosted.size > 3) {
            val bestCost = boosted.minOf { it.cost }
            boosted.removeAll { it.cost > bestCost + 10000 && it.cost > bestCost * 3 }
        }

        // 5. Sort and return
        return boosted.sortedBy { it.cost }.take(limit)
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
    fun posContextCost(reading: String, surface: String, baseCost: Int): Int {
        if (userLearner.lastRightGroup == 0) return baseCost
        val entries = dictManager.dict[reading]
        val entry = entries?.firstOrNull { it.surface == surface } ?: return baseCost
        val connCost = dictManager.getConnectionCost(userLearner.lastRightGroup, entry.leftGroup)
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
    fun kenLmRescore(candidates: MutableList<ConversionCandidate>) {
        val scorer = kenLmScorerProvider() ?: return
        if (!scorer.isReady() || candidates.isEmpty()) return
        if (candidates.size <= 2) return
        val maxScore = 40.coerceAtMost(candidates.size)

        // Use up to 4 words of context for KenLM 5-gram
        val precedingContext = userLearner.committedContext.reversed().joinToString(" ")

        val segmentLists = candidates.take(maxScore).map { c ->
            c.segments.ifEmpty { listOf(c.surface) }
        }

        val scores = scorer.scoreBatchNormalized(segmentLists, precedingContext)

        // Dynamic KenLM weight: longer input = more context = higher trust in LM
        // Short (1-3 chars): weight 1800 — dict cost is more reliable
        // Medium (4-7 chars): weight 2500 — balanced
        // Long (8+ chars): weight 3200 — LM context is crucial
        val totalChars = candidates.firstOrNull()?.reading?.length ?: 4
        val hasContext = userLearner.committedContext.isNotEmpty()
        val dynamicWeight = when {
            // With committed context, even short inputs benefit from LM
            totalChars <= 3 -> if (hasContext) 2200f else 1800f
            totalChars <= 7 -> 2500f
            else -> 3200f
        }

        // Increase weight when we have committed context (cross-sentence scoring)
        // More context words = higher confidence in LM
        val ctxMultiplier = when {
            userLearner.committedContext.size >= 3 -> 1.25f
            userLearner.committedContext.size >= 2 -> 1.20f
            hasContext -> 1.15f
            else -> 1.0f
        }
        val contextWeight = dynamicWeight * ctxMultiplier

        for (i in 0 until maxScore) {
            if (i >= scores.size) break
            // scores[i] is log10 prob (negative; higher = better)
            // Use sqrt normalization to mildly favor longer sequences without over-penalizing
            val wordCount = segmentLists[i].size.coerceAtLeast(1)
            val lmBonus = (scores[i] * -contextWeight / kotlin.math.sqrt(wordCount.toFloat())).toInt()
            candidates[i] = candidates[i].copy(cost = candidates[i].cost + lmBonus)
        }

        // Backward KenLM rescoring: score reversed segments
        // Weight: 0.3 (weaker than forward to avoid over-correction)
        val backwardWeight = 0.3f
        if (maxScore > 1) {
            val reversedSegmentLists = candidates.take(maxScore).map { c ->
                (c.segments.ifEmpty { listOf(c.surface) }).reversed()
            }
            val backwardScores = scorer.scoreBatchNormalized(reversedSegmentLists, "")
            for (i in 0 until minOf(maxScore, backwardScores.size)) {
                val wordCount = reversedSegmentLists[i].size.coerceAtLeast(1)
                val backwardBonus = (backwardScores[i] * -contextWeight * backwardWeight / kotlin.math.sqrt(wordCount.toFloat())).toInt()
                candidates[i] = candidates[i].copy(cost = candidates[i].cost + backwardBonus)
            }
        }
    }

    /**
     * Configure ranking weights based on KenLM model order.
     *
     * 3-gram models have less context, so we reduce LM weights to avoid
     * over-trusting a model with limited history. 5-gram models have
     * more context and get higher weights.
     */
    fun configureWeights(lmOrder: Int) {
        if (lmOrder <= 3) {
            // 3-gram: less context → lower LM weights, gentler rescoring
            viterbiLmWeight = 2200f
            rescoreWeight = 2000f
            contextMultiplier = 1.10f
        } else {
            // 5-gram (or higher): full context → stronger LM influence
            viterbiLmWeight = 3000f
            rescoreWeight = 2500f
            contextMultiplier = 1.25f
        }
    }
}
