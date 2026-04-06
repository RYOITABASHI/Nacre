package space.manus.nacre.ime.input

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import space.manus.nacre.ime.input.DictionaryManager.Companion.isAdjective
import space.manus.nacre.ime.input.DictionaryManager.Companion.isAuxVerb
import space.manus.nacre.ime.input.DictionaryManager.Companion.isContentWord
import space.manus.nacre.ime.input.DictionaryManager.Companion.isNoun
import space.manus.nacre.ime.input.DictionaryManager.Companion.isParticle
import space.manus.nacre.ime.input.DictionaryManager.Companion.isVerb

/**
 * User learning, boost calculation, context management, and persistence.
 *
 * Extracted from ConversionPipeline to separate user-learning concerns
 * from dictionary I/O (DictionaryManager) and Viterbi conversion (ViterbiEngine).
 *
 * Responsibilities:
 * - Unigram / bigram / trigram boost tracking
 * - POS-aware context cost adjustment
 * - KenLM rescoring
 * - Recent history and next-word prediction
 * - User dictionary (register/remove custom words)
 * - Phrase memory and completion
 * - Frequency decay across sessions
 * - Persistence to SharedPreferences
 */
class UserLearner(
    private val context: Context,
    private val dictManager: DictionaryManager,
) {
    // Convenience accessor
    private inline val dict get() = dictManager.dict
    private inline val staticBigrams get() = dictManager.staticBigrams

    // User learning: boost for selected candidates (thread-safe)
    private val userBoost = ConcurrentHashMap<String, Int>(1000)

    // Bigram learning: "prevSurface→reading:surface" → count
    private val bigramBoost = ConcurrentHashMap<String, Int>(500)

    // Trigram learning: "prev2→prev1→reading:surface" → count
    private val trigramBoost = ConcurrentHashMap<String, Int>(300)

    // Recent history: ordered list of recently committed candidates (newest first)
    val recentHistory = java.util.LinkedList<ConversionCandidate>()
    private val maxHistory = 200

    // User dictionary: reading → surface, registered by user (custom words, names, etc.)
    private val userDictionary = ConcurrentHashMap<String, MutableList<UserDictEntry>>(100)

    // Phrase memory: committed multi-word phrases for phrase completion
    // Key: first 2 chars of reading, Value: list of phrase entries
    private val phraseMemory = ConcurrentHashMap<String, MutableList<PhraseEntry>>(200)
    private val maxPhrases = 500

    // Learning epoch: increments each session, used for frequency decay
    private var learningEpoch: Int = 0

    // N-gram context: last 4 committed surfaces (for KenLM 5-gram)
    val committedContext = ArrayDeque<String>(4)

    // Callback for KenLM rescoring — set by ConversionPipeline to candidateRanker::kenLmRescore
    var rescoreFunction: ((MutableList<ConversionCandidate>) -> Unit)? = null

    // Last committed right POS group (for connection cost to next word)
    var lastRightGroup: Int = 0  // BOS/EOS

    // Convenience accessors for backward compat
    val lastCommittedSurface: String get() = committedContext.firstOrNull() ?: ""
    val secondLastCommittedSurface: String get() = committedContext.getOrNull(1) ?: ""

    private var savePending = false
    private var lastSaveTime = 0L

    // --- Loading ---

    /**
     * Load all user learning data from SharedPreferences.
     * Must be called after dictManager.load().
     */
    fun load() {
        loadUserBoost()
        loadUserDictionary()
        loadPhraseMemory()
        injectUserDictionary()
        learningEpoch++
        applyFrequencyDecay()
        saveEpoch()
    }

    // --- Boost calculation ---

    /**
     * Apply user boost (unigram/bigram/trigram/static) to a candidate's cost.
     * Called as a callback from ViterbiEngine.
     */
    fun applyBoost(reading: String, surface: String, baseCost: Int): Int {
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

    // --- Selection recording ---

    /**
     * Record user selection: updates boost, bigram, trigram, history, and POS context.
     */
    fun recordSelection(candidate: ConversionCandidate) {
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

    // --- Context management ---

    fun updateContext(surface: String) {
        committedContext.addFirst(surface)
        while (committedContext.size > 4) committedContext.removeLast()
        lastRightGroup = 0 // BOS/EOS for raw text
    }

    // --- Next word prediction ---

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
            rescoreFunction?.invoke(results)
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

    // --- Debounced save ---

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

    // --- User dictionary ---

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

    // --- Phrase memory ---

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

    // --- Persistence: user boost ---

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
            Log.w("UserLearner", "Failed to load user boost", e)
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
            Log.w("UserLearner", "Failed to save user boost", e)
        }
    }

    // --- Persistence: user dictionary ---

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
            Log.i("UserLearner", "User dictionary loaded: ${userDictionary.values.sumOf { it.size }} entries")
        } catch (e: Exception) {
            Log.w("UserLearner", "Failed to load user dictionary", e)
        }
    }

    private fun saveUserDictionary() {
        try {
            val prefs = context.getSharedPreferences("nacre_user_dict", Context.MODE_PRIVATE)
            val data = userDictionary.values.flatten()
                .joinToString("\n") { "${it.reading}\t${it.surface}\t${it.comment}" }
            prefs.edit().putString("user_dictionary", data).apply()
        } catch (e: Exception) {
            Log.w("UserLearner", "Failed to save user dictionary", e)
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

    // --- Persistence: phrase memory ---

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
            Log.i("UserLearner", "Phrase memory loaded: ${phraseMemory.values.sumOf { it.size }} phrases")
        } catch (e: Exception) {
            Log.w("UserLearner", "Failed to load phrase memory", e)
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
            Log.w("UserLearner", "Failed to save phrase memory", e)
        }
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
            Log.w("UserLearner", "Failed to save epoch", e)
        }
    }
}
