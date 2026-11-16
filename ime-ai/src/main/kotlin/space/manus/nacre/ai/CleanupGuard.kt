package space.manus.nacre.ai

/**
 * Application guard for the LLM dictation cleanup (the "clean" refine
 * preset only — translation / summary / keigo / bullets presets rewrite
 * on purpose and are never gated).
 *
 * Measured motivation (2026-11-17 real-world voice eval,
 * research_notes/nacre-voice-realworld-eval-20261117): 40 clean ASR
 * outputs from noisy conditions (CER already 4–6%) were run through the
 * shipped cleanup prompt with Qwen 2.5 1.5B — 0 recovered, 15 unchanged,
 * 25 DAMAGED; mean CER rose to ~27%. With nothing to clean, the small
 * model paraphrases and drops clauses (「部屋を出るときには明かりを
 * 消しなさい」→「明かりを消しなさい」). Filler-heavy dictation does
 * benefit (the PR #81 golden set), so the fix is gating, not a weaker
 * prompt — the prompt itself is unchanged.
 *
 * Two gates:
 * 1. Pre-gate [needsRefinement]: skip the LLM entirely when the input
 *    shows no cleanup-need signal. A short, clean, well-formed sentence
 *    is committed as-is.
 * 2. Post-gate [isFaithfulRefinement]: when the refined output diverges
 *    too far from the input, fall back to the original text.
 *
 * Calibration (verified by CleanupGuardTest against the recorded
 * data): all 30 PR #81 golden inputs pass the pre-gate and all 30
 * recorded golden outputs are accepted by the post-gate; all 40 eval
 * inputs are skipped by the pre-gate, so the combined guard saves 25/25
 * damaged cases (the post-gate alone would still reject 12/25).
 *
 * Pure JVM logic (no Android dependencies) so it is unit-testable.
 */
object CleanupGuard {

    // ── Pre-gate ─────────────────────────────────────────────────────

    /**
     * Hesitation fillers in their unambiguous elongated forms. Bare
     * あの / その / まあ / なんか are deliberately NOT signals: they are
     * usually content (あの店・その件・まあまあ・なんか＝何か）, and the
     * PR #81 traps depend on keeping them.
     */
    private val FILLERS = listOf(
        "えーっと", "えっと", "えーと", "ええと",
        "あのー", "そのー", "うーん", "えー", "あー",
    )

    /** Self-correction markers: an earlier fragment is superseded later. */
    private val CORRECTIONS = listOf("じゃなくて", "じゃなく", "ではなく", "訂正")

    /**
     * Symbol-reading tokens: an input spelling out ハイフン / ドット / …
     * is a read-aloud command or path that only the LLM converts
     * (「エルエス ハイフン エルエー」→「ls -la」).
     */
    private val COMMAND_TOKENS = listOf(
        "ハイフン", "ドット", "スラッシュ", "アンダーバー", "アスタリスク",
        "バックスラッシュ", "アットマーク", "チルダ", "パイプ", "イコール", "カッコ",
    )

    /**
     * Spoken (kana) numeral run of 3+ numeral kana followed by a counter
     * — raw dictation spells numbers out (にせんにじゅうろくねん), while
     * ASR output for read speech already uses kanji/arabic numerals, so
     * this is a strong raw-dictation signal. Matches overlapping
     * しなさい are excluded: 置きしなさい (置き+しなさい) otherwise
     * false-fires on きしな+さい.
     */
    private val NUMERAL_RE =
        Regex("[いちにさんしよんごろくしちななはちきゅうじゅうひゃくせん]{3,}(?:ねん|がつ|にち|じはん|じ|ふん|ぷん|さい)")
    private val SHINASAI_RE = Regex("しなさい")

    /**
     * Immediate repetition of a phrase: 2–10 chars repeated across a
     * pause separator (、), or 4+ chars repeated adjacently. Adjacent
     * 2-char repeats are excluded on purpose — しばしば / まあまあ are
     * legitimate reduplications, not stutters.
     */
    private val REPETITION_RE =
        Regex("(?:(.{2,10}?)[、。，．\\s]\\1)|(?:(.{4,10}?)\\2)")

    /**
     * Sentence-final forms (polite/plain, past, imperatives, final
     * particles, closing punctuation). An input of reasonable length
     * that does NOT end in one of these is an unfinished dictation
     * fragment （て-form, から, …) that still needs structuring.
     */
    private val SENTENCE_FINAL_RE = Regex(
        "(です|ます|でした|ました|ません|だ|である|ない|なさい|ください|でしょう|だろう" +
            "|よ|ね|わ|か|の|もの|こと|たい|らしい|そうだ|はずだ|のだ|んです|んですか" +
            "|ますか|ですか|ましたか|でしょうか|たいです|たいな|いい|よい|する|なる|ある|いる" +
            "|くれる|ほしい|もらう|しまう|おく|みる|いく|くる|なきゃ|た|る|う" +
            "|[。！？!?])$"
    )

    /** Length at/above which a punctuation-free text counts as raw dictation. */
    private const val LONG_INPUT_CHARS = 48

    /** Fragment-ending detection is only meaningful past this length. */
    private const val FRAGMENT_MIN_CHARS = 12

    /** Names of the cleanup-need signals present in [text] (for diagnostics). */
    fun refinementSignals(text: String): List<String> {
        val signals = mutableListOf<String>()
        if (FILLERS.any { text.contains(it) }) signals += "filler"
        if (CORRECTIONS.any { text.contains(it) }) signals += "correction"
        if (REPETITION_RE.containsMatchIn(text)) signals += "repetition"
        if (COMMAND_TOKENS.any { text.contains(it) }) signals += "command"
        if (hasSpokenNumeral(text)) signals += "numeral"
        if (text.length >= LONG_INPUT_CHARS && text.none { it in "。！？" }) {
            signals += "long-unstructured"
        }
        val core = text.trimEnd('、', ',', ' ')
        if (text.length >= FRAGMENT_MIN_CHARS && !SENTENCE_FINAL_RE.containsMatchIn(core)) {
            signals += "fragment-end"
        }
        return signals
    }

    /** Pre-gate: true when the input shows any sign of needing the LLM cleanup. */
    fun needsRefinement(text: String): Boolean = refinementSignals(text).isNotEmpty()

    private fun hasSpokenNumeral(text: String): Boolean {
        val imperativeRanges = SHINASAI_RE.findAll(text).map { it.range }.toList()
        return NUMERAL_RE.findAll(text).any { m ->
            imperativeRanges.none { r -> m.range.first <= r.last && r.first <= m.range.last }
        }
    }

    // ── Post-gate ────────────────────────────────────────────────────

    /** Normalized length below which a refinement must stay close to the input. */
    private const val SHORT_NORMALIZED_CHARS = 30

    /** Short inputs: minimum normalized Levenshtein similarity to accept. */
    private const val SHORT_MIN_SIMILARITY = 0.65

    /** Short inputs: minimum length ratio (refined / original) to accept. */
    private const val SHORT_MIN_LENGTH_RATIO = 0.60

    /** Long inputs: minimum length ratio — a clause-drop floor. */
    private const val LONG_MIN_LENGTH_RATIO = 0.25

    /**
     * Minimum share of the refined output that must be preserved input
     * content (LCS / refined length). Below this the output is mostly
     * fabrication, whatever its length.
     */
    private const val MIN_OUTPUT_PRESERVATION = 0.50

    /** Self-correction markers, longest first so じゃなくて wins over じゃなく. */
    private val CORRECTION_MARKERS = listOf("じゃなくて", "ではなくて", "じゃなく", "ではなく")

    /** Clause boundaries: a superseded fragment never crosses one of these. */
    private val CLAUSE_BOUNDARIES = "、。，． \t\n"

    /** Longest clause for which every strip position is tried. */
    private const val MAX_VARIANT_CLAUSE_CHARS = 24

    /** Cap on comparison variants returned per input. */
    private const val MAX_VARIANTS = 64

    /** Cap on cumulative repetition-removal steps per input. */
    private const val MAX_DEDUP_STEPS = 8

    /**
     * Cap on variant-tree nodes explored per input. The cap on returned
     * variants alone would not bound the work — branching happens
     * before truncation — so exploration itself is budgeted; when the
     * budget runs out, remaining markers are simply not stripped,
     * which fails closed (toward the original ASR text).
     */
    private const val MAX_EXPANSIONS = 512

    /**
     * Variants of [text] with the material the cleanup is expected to
     * remove taken off. Fillers are removed wholesale. For each
     * self-correction marker, the superseded fragment ends at the
     * marker but where it starts is ambiguous — Japanese has no word
     * boundaries, and the fragment may share its clause with context
     * that survives: in 「会議は火曜じゃなくて水曜です」 only
     * 「火曜じゃなくて」 is superseded, while 「会議は」 is kept. So
     * every strip position inside the clause (bounded by the previous
     * clause boundary) is a candidate, and the post-gate accepts when
     * ANY candidate is consistent with the refined output.
     */
    private fun stripVariants(text: String): List<String> {
        var base = text
        for (filler in FILLERS) base = base.replace(filler, "")
        val variants = mutableListOf<String>()
        variants += correctionVariants(base, 0, VariantBudget(MAX_EXPANSIONS))
        // Repetition removal: the cleanup legitimately deletes one
        // copy of an immediate repetition, which the raw comparison
        // reads as a 50% cut on short inputs (「よろしくお願いします、
        // よろしくお願いします」→「よろしくお願いします。」). The
        // un-deduplicated variant stays in the set, so a refinement
        // that keeps both copies is still accepted via it.
        // Cumulative: each step removes one copy from the result of
        // the previous step, so a phrase repeated three times yields
        // the two-copy and one-copy variants as well. Every removal
        // strictly shortens the text, so the chain always terminates.
        var deduped = base
        repeat(MAX_DEDUP_STEPS) {
            val match = REPETITION_RE.find(deduped) ?: return@repeat
            val unit = match.groups[1] ?: match.groups[2] ?: return@repeat
            deduped = deduped.replaceRange(match.range, unit.value)
            variants += correctionVariants(deduped, 0, VariantBudget(MAX_EXPANSIONS))
        }
        return variants.distinct().take(MAX_VARIANTS)
    }

    /** Shared exploration budget for [correctionVariants]. */
    private class VariantBudget(var remaining: Int)

    private fun correctionVariants(text: String, from: Int, budget: VariantBudget): List<String> {
        if (budget.remaining <= 0) return listOf(text)
        var markerStart = -1
        var markerEnd = -1
        var i = from
        while (i < text.length && markerStart < 0) {
            for (marker in CORRECTION_MARKERS) {
                if (text.startsWith(marker, i)) {
                    markerStart = i
                    markerEnd = i + marker.length
                    break
                }
            }
            i++
        }
        if (markerStart < 0) return listOf(text)
        var clauseStart = markerStart
        while (clauseStart > 0 && text[clauseStart - 1] !in CLAUSE_BOUNDARIES) clauseStart--
        val clauseLength = markerStart - clauseStart
        val keeps: List<Int> = if (clauseLength <= MAX_VARIANT_CLAUSE_CHARS) {
            (0..clauseLength).toList()
        } else {
            // Too long to enumerate: whole-clause strip or marker-only strip.
            listOf(0, clauseLength)
        }
        val results = mutableListOf<String>()
        for (keep in keeps) {
            if (budget.remaining <= 0) break
            budget.remaining--
            val stripped = text.substring(0, clauseStart + keep) + text.substring(markerEnd)
            results += correctionVariants(stripped, clauseStart + keep, budget)
            if (results.size >= MAX_VARIANTS) break
        }
        if (results.isEmpty()) results += text
        return results.distinct().take(MAX_VARIANTS)
    }

    /**
     * Post-gate: is [refined] a faithful cleanup of [original]?
     *
     * The metric is deliberately length-aware. On short inputs the
     * measured damage (paraphrase, clause drop) moves the text a lot
     * relative to its size, while legitimate short cleanups barely move
     * the stripped text, so short inputs get a strict similarity +
     * shrink check. On long inputs, legitimate dictation cleanup
     * restructures heavily (recorded golden pairs go down to 0.21
     * similarity / 0.30 length ratio when abandoned fragments and
     * self-corrections are resolved), so only the fabrication /
     * clause-drop floors apply.
     *
     * Two inputs are exempt from the metric entirely: symbol readings
     * and spoken numerals, whose legitimate outputs are representation
     * changes the string metric cannot see at all （「エルエス ハイフン
     * エルエー」→「ls -la」, 「にせんにじゅうろくねん」→「2026年」
     * share almost no characters with the input). Those inputs passed
     * the pre-gate precisely because they need the LLM.
     *
     * Comparison is done on punctuation-stripped text: cleanup
     * legitimately inserts/removes 、。？ and converts digits, and
     * punctuation must not count as divergence.
     */
    fun isFaithfulRefinement(original: String, refined: String): Boolean {
        if (COMMAND_TOKENS.any { original.contains(it) } || hasSpokenNumeral(original)) {
            return true
        }
        val b = normalize(refined)
        if (b.isEmpty()) return false
        return stripVariants(original).any { variant ->
            val a = normalize(variant)
            a.isNotEmpty() && isFaithfulVariant(a, b)
        }
    }

    /** The length-aware divergence check for one stripped variant. */
    private fun isFaithfulVariant(a: String, b: String): Boolean {
        if (lcsLength(a, b).toDouble() / b.length < MIN_OUTPUT_PRESERVATION) return false
        val ratio = b.length.toDouble() / a.length
        return if (a.length < SHORT_NORMALIZED_CHARS) {
            similarity(a, b) >= SHORT_MIN_SIMILARITY && ratio >= SHORT_MIN_LENGTH_RATIO
        } else {
            ratio >= LONG_MIN_LENGTH_RATIO
        }
    }

    private val STRIPPED_CHARS = "、。！？!?・「」『』（）()【】〜…‥：；:;,.".toSet()

    private fun normalize(text: String): String =
        text.filterNot { it in STRIPPED_CHARS || it.isWhitespace() }

    /** Normalized Levenshtein similarity: 1 − distance / max length. */
    private fun similarity(a: String, b: String): Double {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun lcsLength(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        var prev = IntArray(b.length + 1)
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            for (j in 1..b.length) {
                cur[j] = if (a[i - 1] == b[j - 1]) {
                    prev[j - 1] + 1
                } else {
                    maxOf(prev[j], cur[j - 1])
                }
            }
            prev = cur
        }
        return prev[b.length]
    }
}
