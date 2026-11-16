package space.manus.nacre.ime.input

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Swipe (glide) typing for the QWERTY layer — Nacre's own decoder.
 *
 * Why not FUTO Swipe's published models (Oct 2026)? Two reasons, verified
 * against the official sources before this was written:
 *  - License: the weights ship under the "FUTO Model Weights License 1.0" —
 *    a custom source-available license that is non-sublicensable and
 *    non-transferable, mandates a visible "powered by FUTO Swipe" notice to
 *    end users, and forces derivative models to carry the same terms. That
 *    cannot be bundled into an Apache-2.0 app without dragging non-Apache
 *    terms into its distribution. (The FUTO Keyboard source itself is under
 *    the FUTO Source First License 1.1-kb — non-commercial modification and
 *    distribution only.)
 *  - Coverage: FUTO's decoder is English-QWERTY only; Nacre also needs the
 *    romaji path for Japanese, which it could never provide.
 *
 * So this is the classic pre-neural architecture, built entirely from
 * Nacre's own assets: touch trace → per-key proximity → beam search over a
 * dictionary trie → rerank by how much of the trace the candidate's ideal
 * path actually explains (coverage). No third-party weights or code.
 *
 * Everything in this file is pure Kotlin (no Android/Compose types) so the
 * decoder is unit-testable on a plain JVM with synthetic traces.
 */

/** One sampled point of a glide trace, in keyboard-root coordinates (px). */
data class SwipeTracePoint(val x: Float, val y: Float)

/** Geometry of one letter key, in the same coordinate space as the trace. */
data class SwipeKeyBox(
    val char: Char,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
)

/**
 * One lexicon entry for swipe decoding. [word] is the decoded form the beam
 * search matches (lowercase key), [surface] is what gets committed (the
 * dictionary's display form, e.g. "I" for the key "i"). [cost] follows the
 * dictionary convention: lower = more frequent.
 */
data class SwipeLexiconWord(
    val word: String,
    val cost: Int,
    val surface: String = word,
)

/** A decoded candidate; [score] is a cost — lower is better. */
data class SwipeCandidate(
    val word: String,
    val surface: String,
    val score: Double,
)

/**
 * Live registry of QWERTY letter-key geometry. Key views register their
 * bounds (root coordinates) on layout and unregister on dispose, so the
 * registry tracks whatever layout variant is currently on screen without
 * any layout math duplicated here.
 */
class SwipeGeometry {
    private val boxes = ConcurrentHashMap<Char, SwipeKeyBox>()

    fun register(box: SwipeKeyBox) {
        boxes[box.char] = box
    }

    fun unregister(char: Char) {
        boxes.remove(char)
    }

    fun clear() {
        boxes.clear()
    }

    fun snapshot(): List<SwipeKeyBox> = boxes.values.toList()
}

/** Trie over a swipe lexicon. Built once per dictionary load and reused. */
class SwipeLexicon(words: List<SwipeLexiconWord>) {

    class Node {
        val children = HashMap<Char, Node>()
        var terminal: SwipeLexiconWord? = null
    }

    val root = Node()

    /** Number of words actually inserted (after length filtering). */
    val size: Int

    init {
        var count = 0
        for (entry in words) {
            val word = entry.word
            if (word.length < MIN_WORD_LENGTH || word.length > MAX_WORD_LENGTH) continue
            var node = root
            for (ch in word) {
                node = node.children.getOrPut(ch) { Node() }
            }
            // Duplicate decoded forms (e.g. two kana readings mapping to the
            // same romaji): keep the more frequent (lower-cost) entry.
            val existing = node.terminal
            if (existing == null || entry.cost < existing.cost) {
                node.terminal = entry
            }
            count++
        }
        size = count
    }

    companion object {
        const val MIN_WORD_LENGTH = 2
        const val MAX_WORD_LENGTH = 16
    }
}

/**
 * Beam-search swipe decoder.
 *
 * Scoring, per candidate word:
 *  - spatial: each letter of the word is aligned, in order, to a distinct
 *    trace point near that letter's key (Gaussian-style cost in half-key
 *    units). Points may be skipped at a small constant penalty.
 *  - prior: the dictionary frequency cost, log-scaled so it breaks ties
 *    between geometrically similar words without overriding clear spatial
 *    evidence.
 *  - endpoint: the trace should start on the word's first key and end on
 *    its last key.
 *  - coverage (rerank): the mean distance from trace points to the
 *    candidate's ideal polyline (through its letters' key centers). This
 *    is what separates "world" from "word" on a trace that detours through
 *    L — the shorter word leaves that whole excursion unexplained.
 */
class SwipeDecoder(private val lexicon: SwipeLexicon) {

    private class BeamState(
        val node: SwipeLexicon.Node,
        val word: String,
        val score: Double,
    )

    fun decode(
        trace: List<SwipeTracePoint>,
        keys: List<SwipeKeyBox>,
        limit: Int = 8,
    ): List<SwipeCandidate> {
        if (trace.size < 2 || keys.isEmpty() || lexicon.size == 0) return emptyList()
        val keyByChar = HashMap<Char, SwipeKeyBox>(keys.size)
        for (box in keys) keyByChar[box.char] = box
        val keyUnit = medianKeySize(keys)
        if (keyUnit <= 0f) return emptyList()

        val points = resampleByArcLength(trace, MAX_SAMPLES)
        val totalLength = arcLength(trace)
        // A glide travels; a tap (or a short directional flick on one key)
        // does not. Below this the gesture is not a word glide at all.
        if (totalLength < keyUnit * MIN_TRAVEL_KEY_UNITS) return emptyList()

        // --- Beam search over (trie node × trace point) ---
        var beam: List<BeamState> = listOf(BeamState(lexicon.root, "", 0.0))
        // Best completed-word sightings, harvested from the last part of the
        // trace so a word that completes just before a small trailing
        // overshoot still counts. Value = (score normalized to full length).
        val harvested = HashMap<String, BeamState>()
        val harvestFrom = (points.size * 0.6).toInt()
        for (i in points.indices) {
            val point = points[i]
            val next = HashMap<SwipeLexicon.Node, BeamState>()
            fun offer(state: BeamState) {
                val prev = next[state.node]
                if (prev == null || state.score < prev.score) next[state.node] = state
            }
            for (state in beam) {
                // Skip this point (it adds no letter).
                offer(BeamState(state.node, state.word, state.score + SKIP_PENALTY))
                // Advance: consume this point as the next letter, if a trie
                // child exists whose key is near the point.
                for ((letter, child) in state.node.children) {
                    val box = keyByChar[letter] ?: continue
                    val nd = normalizedDistance(point, box)
                    if (nd > MAX_ADVANCE_ND) continue
                    offer(
                        BeamState(
                            child,
                            state.word + letter,
                            state.score + nd * nd * SPATIAL_WEIGHT,
                        ),
                    )
                }
            }
            beam = next.values.sortedBy { it.score }.take(BEAM_WIDTH)
            if (i >= harvestFrom) {
                val remainingPenalty = (points.size - 1 - i) * SKIP_PENALTY
                for (state in beam) {
                    if (state.node.terminal != null && state.word.length >= SwipeLexicon.MIN_WORD_LENGTH) {
                        val normalized = BeamState(state.node, state.word, state.score + remainingPenalty)
                        val prev = harvested[state.word]
                        if (prev == null || normalized.score < prev.score) {
                            harvested[state.word] = normalized
                        }
                    }
                }
            }
            if (beam.isEmpty()) break
        }

        // --- Final scoring: prior + endpoints, then coverage rerank ---
        val first = points.first()
        val last = points.last()
        val scored = ArrayList<SwipeCandidate>(harvested.size)
        for ((word, state) in harvested) {
            val entry = state.node.terminal ?: continue
            val firstBox = keyByChar[word.first()] ?: continue
            val lastBox = keyByChar[word.last()] ?: continue
            val ndStart = normalizedDistance(first, firstBox)
            val ndEnd = normalizedDistance(last, lastBox)
            // The word must plausibly start and end where the finger did.
            if (ndStart > MAX_ENDPOINT_ND || ndEnd > MAX_ENDPOINT_ND) continue
            val prior = PRIOR_WEIGHT * ln(1.0 + entry.cost / PRIOR_COST_SCALE)
            val score = state.score + prior + ENDPOINT_WEIGHT * (ndStart + ndEnd)
            scored.add(SwipeCandidate(word, entry.surface, score))
        }
        if (scored.isEmpty()) return emptyList()
        scored.sortBy { it.score }
        val shortlist = scored.take(RERANK_POOL)
        val reranked = shortlist.map { cand ->
            val penalty = coveragePenalty(points, cand.word, keyByChar)
            SwipeCandidate(cand.word, cand.surface, cand.score + COVERAGE_WEIGHT * penalty)
        }.sortedBy { it.score }
        return reranked.take(limit)
    }

    companion object {
        private const val MAX_SAMPLES = 40
        private const val BEAM_WIDTH = 240
        private const val SKIP_PENALTY = 0.03
        private const val SPATIAL_WEIGHT = 1.0
        private const val MAX_ADVANCE_ND = 2.4
        private const val MAX_ENDPOINT_ND = 3.0
        private const val PRIOR_WEIGHT = 0.5
        private const val PRIOR_COST_SCALE = 600.0
        private const val ENDPOINT_WEIGHT = 0.8
        private const val RERANK_POOL = 24
        private const val COVERAGE_WEIGHT = 6.0
        private const val COVERAGE_CAP_KEY_UNITS = 1.2
        private const val MIN_TRAVEL_KEY_UNITS = 0.9

        /** Normalized distance: 1.0 ≈ the key's edge from its center. */
        internal fun normalizedDistance(point: SwipeTracePoint, box: SwipeKeyBox): Double {
            val dx = (point.x - box.centerX) / (box.width / 2f)
            val dy = (point.y - box.centerY) / (box.height / 2f)
            return sqrt((dx * dx + dy * dy).toDouble())
        }

        private fun medianKeySize(keys: List<SwipeKeyBox>): Float {
            if (keys.isEmpty()) return 0f
            val widths = keys.map { it.width }.sorted()
            return widths[widths.size / 2]
        }

        internal fun arcLength(trace: List<SwipeTracePoint>): Float {
            var total = 0f
            for (i in 1 until trace.size) {
                total += hypot(trace[i].x - trace[i - 1].x, trace[i].y - trace[i - 1].y)
            }
            return total
        }

        /**
         * Resample a trace to at most [maxPoints] points spaced uniformly by
         * arc length (endpoints preserved exactly). Uniform spatial spacing
         * removes the finger's speed profile from the scoring: slow dwells
         * no longer dominate fast transits.
         */
        internal fun resampleByArcLength(
            trace: List<SwipeTracePoint>,
            maxPoints: Int,
        ): List<SwipeTracePoint> {
            if (trace.size <= 2) return trace
            val total = arcLength(trace)
            if (total <= 0f) return listOf(trace.first())
            val segments = maxPoints - 1
            val step = total / segments
            val out = ArrayList<SwipeTracePoint>(maxPoints)
            out.add(trace.first())
            var target = step
            var walked = 0f
            var i = 1
            while (i < trace.size && out.size < maxPoints - 1) {
                val segLen = hypot(trace[i].x - trace[i - 1].x, trace[i].y - trace[i - 1].y)
                if (segLen > 0f && walked + segLen >= target) {
                    val t = (target - walked) / segLen
                    out.add(
                        SwipeTracePoint(
                            trace[i - 1].x + (trace[i].x - trace[i - 1].x) * t,
                            trace[i - 1].y + (trace[i].y - trace[i - 1].y) * t,
                        ),
                    )
                    target += step
                    continue // same segment may hold more sample points
                }
                walked += segLen
                i++
            }
            out.add(trace.last())
            return out
        }

        /**
         * Mean distance from trace points to the candidate word's ideal
         * path (the polyline through its letters' key centers), capped per
         * point so one wild excursion can't dominate. In key units.
         */
        internal fun coveragePenalty(
            points: List<SwipeTracePoint>,
            word: String,
            keyByChar: Map<Char, SwipeKeyBox>,
        ): Double {
            val centers = word.mapNotNull { keyByChar[it] }
            if (centers.size < 2 || points.isEmpty()) return 0.0
            val keyUnit = centers.map { it.width }.sorted()[centers.size / 2]
            if (keyUnit <= 0f) return 0.0
            var sum = 0.0
            for (p in points) {
                var best = Double.MAX_VALUE
                for (j in 1 until centers.size) {
                    val d = pointToSegmentDistance(p, centers[j - 1], centers[j])
                    if (d < best) best = d
                }
                // A word with a doubled letter has a zero-length segment;
                // also measure distance to the vertices themselves.
                for (c in centers) {
                    val d = hypot(p.x - c.centerX, p.y - c.centerY).toDouble()
                    if (d < best) best = d
                }
                sum += minOf(best / keyUnit, COVERAGE_CAP_KEY_UNITS.toDouble())
            }
            return sum / points.size
        }

        private fun pointToSegmentDistance(
            p: SwipeTracePoint,
            a: SwipeKeyBox,
            b: SwipeKeyBox,
        ): Double {
            val abx = b.centerX - a.centerX
            val aby = b.centerY - a.centerY
            val lenSq = abx * abx + aby * aby
            if (lenSq == 0f) return hypot(p.x - a.centerX, p.y - a.centerY).toDouble()
            val t = (((p.x - a.centerX) * abx + (p.y - a.centerY) * aby) / lenSq)
                .coerceIn(0f, 1f)
            val projX = a.centerX + abx * t
            val projY = a.centerY + aby * t
            return hypot(p.x - projX, p.y - projY).toDouble()
        }

        /**
         * The letter sequence a trace passes over (nearest key per sample,
         * consecutive duplicates collapsed). Used as the fallback when a
         * glide decodes to nothing: the gesture replays as if those keys
         * had been tapped in sequence, through the ordinary input path.
         */
        fun lettersAlongPath(
            trace: List<SwipeTracePoint>,
            keys: List<SwipeKeyBox>,
        ): String {
            if (trace.isEmpty() || keys.isEmpty()) return ""
            val points = resampleByArcLength(trace, MAX_SAMPLES)
            val out = StringBuilder()
            for (p in points) {
                var bestChar: Char? = null
                var bestNd = Double.MAX_VALUE
                for (box in keys) {
                    val nd = normalizedDistance(p, box)
                    if (nd < bestNd) {
                        bestNd = nd
                        bestChar = box.char
                    }
                }
                val ch = bestChar ?: continue
                if (bestNd <= 1.05 && (out.isEmpty() || out[out.length - 1] != ch)) {
                    out.append(ch)
                }
            }
            return out.toString()
        }
    }
}

/**
 * Hiragana → romaji (canonical Hepburn-style spellings matching
 * [JapaneseEngine]'s table), used to build the romaji swipe lexicon from
 * kana dictionary readings.
 *
 * This is a generation aid, not a source of truth: every generated romaji
 * string is validated by feeding it back through
 * [JapaneseEngine.romajiToHiragana] and requiring an exact round-trip
 * before it enters the lexicon, so a wrong canonical choice here can only
 * ever drop a word from glide decoding, never corrupt it.
 */
object KanaRomaji {

    fun hiraganaToRomaji(kana: String): String? {
        val out = StringBuilder()
        var prevVowel: Char? = null
        var i = 0
        while (i < kana.length) {
            val c = kana[i]
            when {
                c == 'っ' -> {
                    // Double the next syllable's initial consonant.
                    val next = syllableAt(kana, i + 1) ?: return null
                    val initial = next.first()
                    if (initial in "aiueon") return null
                    out.append(initial)
                    i++
                }
                c == 'ん' -> {
                    // JapaneseEngine's rules: a single "n" becomes ん only
                    // before a consonant (or at finalize). Before a vowel,
                    // a y-row or an n-row syllable it would merge into that
                    // syllable ("na", "nya", "ni"…), so ん must be written
                    // "nn" there — the engine's nn rule consumes both n's
                    // as ん and leaves the following syllable intact.
                    val nextInitial = peekInitial(kana, i + 1)
                    if (nextInitial != null && (nextInitial in "aiueoy" || nextInitial == 'n')) {
                        out.append("nn")
                    } else {
                        out.append('n')
                    }
                    prevVowel = null
                    i++
                }
                c == 'ー' -> {
                    val v = prevVowel ?: return null
                    out.append(v)
                    i++
                }
                else -> {
                    val romaji = syllableAt(kana, i) ?: return null
                    out.append(romaji)
                    prevVowel = romaji.last()
                    i += syllableLengthAt(kana, i)
                }
            }
        }
        return out.toString()
    }

    /**
     * First romaji letter of whatever follows at [index] — used only to
     * decide how ん must be spelled. ん itself reports 'n'; end of string
     * and unconvertible positions report null.
     */
    private fun peekInitial(kana: String, index: Int): Char? {
        if (index >= kana.length) return null
        if (kana[index] == 'ん') return 'n'
        return syllableAt(kana, index)?.first()
    }

    /** Romaji of the syllable starting at [index], or null if none. */
    private fun syllableAt(kana: String, index: Int): String? {
        if (index >= kana.length) return null
        if (index + 1 < kana.length) {
            TWO_CHAR[kana.substring(index, index + 2)]?.let { return it }
        }
        return ONE_CHAR[kana[index]]
    }

    private fun syllableLengthAt(kana: String, index: Int): Int {
        if (index + 1 < kana.length && TWO_CHAR.containsKey(kana.substring(index, index + 2))) {
            return 2
        }
        return 1
    }

    private val ONE_CHAR: Map<Char, String> = mapOf(
        'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
        'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
        'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
        'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
        'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
        'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
        'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
        'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
        'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
        'わ' to "wa", 'を' to "wo",
        'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
        'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
        'だ' to "da", 'ぢ' to "di", 'づ' to "du", 'で' to "de", 'ど' to "do",
        'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
        'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po",
        'ゔ' to "vu",
        // Small kana via the x* spellings JapaneseEngine accepts.
        'ぁ' to "xa", 'ぃ' to "xi", 'ぅ' to "xu", 'ぇ' to "xe", 'ぉ' to "xo",
        'ゃ' to "xya", 'ゅ' to "xyu", 'ょ' to "xyo", 'ゎ' to "xwa",
    )

    private val TWO_CHAR: Map<String, String> = mapOf(
        "きゃ" to "kya", "きぃ" to "kyi", "きゅ" to "kyu", "きぇ" to "kye", "きょ" to "kyo",
        "しゃ" to "sha", "しぃ" to "syi", "しゅ" to "shu", "しぇ" to "she", "しょ" to "sho",
        "ちゃ" to "cha", "ちぃ" to "tyi", "ちゅ" to "chu", "ちぇ" to "che", "ちょ" to "cho",
        "にゃ" to "nya", "にぃ" to "nyi", "にゅ" to "nyu", "にぇ" to "nye", "にょ" to "nyo",
        "ひゃ" to "hya", "ひぃ" to "hyi", "ひゅ" to "hyu", "ひぇ" to "hye", "ひょ" to "hyo",
        "みゃ" to "mya", "みぃ" to "myi", "みゅ" to "myu", "みぇ" to "mye", "みょ" to "myo",
        "りゃ" to "rya", "りぃ" to "ryi", "りゅ" to "ryu", "りぇ" to "rye", "りょ" to "ryo",
        "ぎゃ" to "gya", "ぎぃ" to "gyi", "ぎゅ" to "gyu", "ぎぇ" to "gye", "ぎょ" to "gyo",
        "じゃ" to "ja", "じぃ" to "jyi", "じゅ" to "ju", "じぇ" to "je", "じょ" to "jo",
        "ぢゃ" to "dya", "ぢぃ" to "dyi", "ぢゅ" to "dyu", "ぢぇ" to "dye", "ぢょ" to "dyo",
        "びゃ" to "bya", "びぃ" to "byi", "びゅ" to "byu", "びぇ" to "bye", "びょ" to "byo",
        "ぴゃ" to "pya", "ぴぃ" to "pyi", "ぴゅ" to "pyu", "ぴぇ" to "pye", "ぴょ" to "pyo",
        "ふぁ" to "fa", "ふぃ" to "fi", "ふぇ" to "fe", "ふぉ" to "fo",
        "ふゃ" to "fya", "ふゅ" to "fyu", "ふょ" to "fyo",
        "てぃ" to "thi", "てぇ" to "the", "てゃ" to "tha", "てょ" to "tho", "てゅ" to "thu",
        "でぃ" to "dhi", "でぇ" to "dhe", "でゃ" to "dha", "でょ" to "dho", "でゅ" to "dhu",
        "とぁ" to "twa", "とぃ" to "twi", "とぅ" to "twu", "とぇ" to "twe", "とぉ" to "two",
        "どぁ" to "dwa", "どぃ" to "dwi", "どぅ" to "dwu", "どぇ" to "dwe", "どぉ" to "dwo",
        "ぐぁ" to "gwa", "ぐぃ" to "gwi", "ぐぅ" to "gwu", "ぐぇ" to "gwe", "ぐぉ" to "gwo",
        "くぁ" to "qa", "くぃ" to "qi", "くぇ" to "qe", "くぉ" to "qo",
        "つぁ" to "tsa", "つぃ" to "tsi", "つぇ" to "tse", "つぉ" to "tso",
        "ゔぁ" to "va", "ゔぃ" to "vi", "ゔぇ" to "ve", "ゔぉ" to "vo",
        "うぁ" to "wha", "うぃ" to "wi", "うぇ" to "whe", "うぉ" to "who",
    )
}
