package space.manus.nacre.ai

/**
 * Voice command types that can be triggered by speech input.
 * Thread-safe: all objects are stateless singletons.
 */
sealed class VoiceCommand {
    object NewLine : VoiceCommand()
    /** Insert 。 */
    object Period : VoiceCommand()
    /** Insert 、 */
    object Comma : VoiceCommand()
    object Undo : VoiceCommand()
    object Commit : VoiceCommand()
    /** Delete all committed text */
    object ClearAll : VoiceCommand()
    /** Insert a literal space */
    object Space : VoiceCommand()
    /** Insert （ */
    object OpenParen : VoiceCommand()
    /** Insert ） */
    object CloseParen : VoiceCommand()
}

data class ProcessResult(
    val text: String,
    val command: VoiceCommand? = null
)

/**
 * Production-quality post-processor for Whisper speech-to-text output.
 *
 * Handles:
 * - Filler word removal (Japanese & English, with elongation)
 * - Self-correction detection ("じゃなくて", "no wait", etc.)
 * - Auto-punctuation (。？、. ? based on content analysis)
 * - Voice command detection (改行, undo, etc.)
 * - Whisper hallucination filtering (ご視聴ありがとうございました, etc.)
 * - Number/date normalization (漢数字 → Arabic)
 * - Text normalization (whitespace, full-width/half-width)
 *
 * Thread-safe: all mutable state is local to method calls.
 * All pattern lists and maps in [Companion] are immutable.
 */
class PostProcessor {

    companion object {

        // ────────────────────────────────────────────────────
        //  Filler patterns
        // ────────────────────────────────────────────────────

        /**
         * Japanese fillers with elongation support.
         * ー can repeat arbitrarily (えーーーーーと, あのーーーー).
         * Ordered longest-match-first to avoid partial consumption.
         */
        private val JA_FILLER_PATTERNS: List<Regex> = listOf(
            // Multi-char fillers (longest first)
            Regex("ええー*と"),       // ええと, ええーと
            Regex("えー+と"),        // えーと, えーーーーーと
            Regex("えっと"),         // えっと
            Regex("んー*と"),        // んーと, んと
            Regex("うー+ん"),        // うーん, うーーーん
            Regex("うー+"),         // うー (without ん)
            Regex("あー+"),         // あー, あーーー
            Regex("ああ+"),         // ああ, あああ
            Regex("えー+"),         // えー, えーーー
            Regex("んー+"),         // んー (without と)
            Regex("まあ+"),         // まあ, まああ
            Regex("なんか"),         // なんか
            Regex("ほら"),          // ほら
        ).map { base ->
            // Allow optional trailing whitespace (half-width or full-width)
            Regex("${base.pattern}[\\s\u3000]*")
        }

        /**
         * Nouns that follow あの/その when used as demonstratives (not fillers).
         * If あの/その is directly followed by one of these, it must be kept.
         */
        private val DEMONSTRATIVE_NOUN_PREFIXES: List<String> = listOf(
            "人", "時", "日", "方", "子", "件", "話", "場合", "後", "前",
            "頃", "辺", "店", "会社", "仕事", "問題", "事", "物", "本",
            "映画", "音楽", "車", "家", "部屋", "先生", "学生", "女の子",
            "男の子", "子ども", "友達", "こと", "もの",
        )

        /**
         * Regex matching あの or その (with optional elongation) used as fillers.
         * Context-aware: only matches when NOT followed by a demonstrative noun.
         * (Applied separately in removeFiller to allow lookahead logic.)
         */
        private val ANO_SONO_FILLER = Regex("(あの|その)ー*[\\s\u3000]*")

        /**
         * English fillers split into "safe anywhere" and "start-only" groups.
         * Words like "so", "like", "well", "actually", "literally", "right",
         * "okay so" are too ambiguous mid-sentence and only removed at the start.
         */
        private val EN_FILLER_PATTERNS: List<Regex> = run {
            // Safe to remove anywhere in the sentence
            val anywhereSafe = listOf(
                "uh-huh", "you know", "I mean", "um", "uh", "basically"
            )
            // Only remove when at the very start of the text
            val startOnly = listOf(
                "so", "like", "well", "actually", "literally", "right", "okay so"
            )

            val patterns = mutableListOf<Regex>()
            for (filler in anywhereSafe) {
                // \b word boundaries, optional trailing comma + space
                patterns.add(Regex("\\b${Regex.escape(filler)}\\b[,]?\\s?", RegexOption.IGNORE_CASE))
            }
            for (filler in startOnly) {
                // Only match at string start (after optional whitespace)
                patterns.add(Regex("^\\s*\\b${Regex.escape(filler)}\\b[,]?\\s?", RegexOption.IGNORE_CASE))
            }
            patterns
        }

        // ────────────────────────────────────────────────────
        //  Self-correction patterns
        // ────────────────────────────────────────────────────

        /** Japanese correction markers. Group 1 = discarded, Group 2 = kept. */
        private val JA_CORRECTION_PATTERNS = listOf(
            Regex("(.*)じゃなくて(.+)"),
            Regex("(.*)ではなくて?(.+)"),
            Regex("(.*)じゃない[、,]?\\s*(.+)"),
            Regex("(.*)違う[、,]?\\s*(.+)"),
            Regex("(.*)ちがう[、,]?\\s*(.+)"),
            Regex("(.*)間違えた[、,]?\\s*(.+)"),
            Regex("(.*)訂正[、,]?\\s*(.+)"),
            Regex("(.*)もとい[、,]?\\s*(.+)"),
            Regex("(.*)いや[、,]\\s*(.+)"),  // いや requires comma to avoid false positives
        )

        /** English correction markers. Case-insensitive. */
        private val EN_CORRECTION_PATTERNS = listOf(
            Regex("(.*)\\bno wait\\b[,]?\\s*(.+)", RegexOption.IGNORE_CASE),
            Regex("(.*)\\bI mean\\b[,]?\\s*(.+)", RegexOption.IGNORE_CASE),
            Regex("(.*)\\bsorry\\b[,]?\\s*(.+)", RegexOption.IGNORE_CASE),
            Regex("(.*)\\bactually\\b[,]?\\s*(.+)", RegexOption.IGNORE_CASE),
            Regex("(.*)\\bcorrection\\b[,:]?\\s*(.+)", RegexOption.IGNORE_CASE),
            Regex("(.*)\\brather\\b[,]?\\s*(.+)", RegexOption.IGNORE_CASE),
            Regex("(.*)\\blet me rephrase\\b[,.]?\\s*(.+)", RegexOption.IGNORE_CASE),
        )

        // ────────────────────────────────────────────────────
        //  Voice commands
        // ────────────────────────────────────────────────────

        /** Exact-match map: the entire chunk is a command. Keys are lowercase. */
        private val VOICE_COMMANDS_EXACT: Map<String, VoiceCommand> = mapOf(
            // NewLine
            "改行" to VoiceCommand.NewLine,
            "かいぎょう" to VoiceCommand.NewLine,
            "new line" to VoiceCommand.NewLine,
            "newline" to VoiceCommand.NewLine,
            "enter" to VoiceCommand.NewLine,
            // Period
            "句点" to VoiceCommand.Period,
            "まる" to VoiceCommand.Period,
            "period" to VoiceCommand.Period,
            "dot" to VoiceCommand.Period,
            // Comma
            "読点" to VoiceCommand.Comma,
            "てん" to VoiceCommand.Comma,
            "comma" to VoiceCommand.Comma,
            // Undo
            "消して" to VoiceCommand.Undo,
            "けして" to VoiceCommand.Undo,
            "取り消し" to VoiceCommand.Undo,
            "undo" to VoiceCommand.Undo,
            "delete" to VoiceCommand.Undo,
            // Commit
            "確定" to VoiceCommand.Commit,
            "かくてい" to VoiceCommand.Commit,
            "送信" to VoiceCommand.Commit,
            "done" to VoiceCommand.Commit,
            "commit" to VoiceCommand.Commit,
            "send" to VoiceCommand.Commit,
            // ClearAll
            "全部消して" to VoiceCommand.ClearAll,
            "ぜんぶけして" to VoiceCommand.ClearAll,
            "clear all" to VoiceCommand.ClearAll,
            // Space
            "スペース" to VoiceCommand.Space,
            "すぺーす" to VoiceCommand.Space,
            "space" to VoiceCommand.Space,
            // Parentheses
            "かっこ" to VoiceCommand.OpenParen,
            "括弧" to VoiceCommand.OpenParen,
            "parenthesis" to VoiceCommand.OpenParen,
            "open paren" to VoiceCommand.OpenParen,
            "かっことじ" to VoiceCommand.CloseParen,
            "括弧閉じ" to VoiceCommand.CloseParen,
            "close paren" to VoiceCommand.CloseParen,
            "close parenthesis" to VoiceCommand.CloseParen,
        )

        /**
         * Suffix patterns for detecting commands at the end of a chunk.
         * The regex matches optional separator (space, punctuation) + the command word at end-of-string.
         * Ordered longest-first so longer matches take priority.
         */
        private val VOICE_COMMAND_SUFFIXES: List<Pair<Regex, VoiceCommand>> = listOf(
            // NewLine
            Regex("[\\s\u3000、。,.]?改行$") to VoiceCommand.NewLine,
            Regex("[\\s\u3000、。,.]?かいぎょう$") to VoiceCommand.NewLine,
            Regex("[\\s\u3000、。,.]+new\\s*line$", RegexOption.IGNORE_CASE) to VoiceCommand.NewLine,
            Regex("[\\s\u3000、。,.]+enter$", RegexOption.IGNORE_CASE) to VoiceCommand.NewLine,
            // Period
            Regex("[\\s\u3000、。,.]?句点$") to VoiceCommand.Period,
            Regex("[\\s\u3000、。,.]?まる$") to VoiceCommand.Period,
            Regex("[\\s\u3000、。,.]+period$", RegexOption.IGNORE_CASE) to VoiceCommand.Period,
            Regex("[\\s\u3000、。,.]+dot$", RegexOption.IGNORE_CASE) to VoiceCommand.Period,
            // Comma
            Regex("[\\s\u3000、。,.]?読点$") to VoiceCommand.Comma,
            Regex("[\\s\u3000、。,.]?てん$") to VoiceCommand.Comma,
            Regex("[\\s\u3000、。,.]+comma$", RegexOption.IGNORE_CASE) to VoiceCommand.Comma,
            // ClearAll (must come before Undo — "全部消して" contains "消して")
            Regex("[\\s\u3000、。,.]?全部消して$") to VoiceCommand.ClearAll,
            Regex("[\\s\u3000、。,.]?ぜんぶけして$") to VoiceCommand.ClearAll,
            Regex("[\\s\u3000、。,.]+clear\\s+all$", RegexOption.IGNORE_CASE) to VoiceCommand.ClearAll,
            // Undo
            Regex("[\\s\u3000、。,.]?消して$") to VoiceCommand.Undo,
            Regex("[\\s\u3000、。,.]?取り消し$") to VoiceCommand.Undo,
            Regex("[\\s\u3000、。,.]+undo$", RegexOption.IGNORE_CASE) to VoiceCommand.Undo,
            Regex("[\\s\u3000、。,.]+delete$", RegexOption.IGNORE_CASE) to VoiceCommand.Undo,
            // Commit
            Regex("[\\s\u3000、。,.]?確定$") to VoiceCommand.Commit,
            Regex("[\\s\u3000、。,.]?送信$") to VoiceCommand.Commit,
            Regex("[\\s\u3000、。,.]+done$", RegexOption.IGNORE_CASE) to VoiceCommand.Commit,
            Regex("[\\s\u3000、。,.]+commit$", RegexOption.IGNORE_CASE) to VoiceCommand.Commit,
            Regex("[\\s\u3000、。,.]+send$", RegexOption.IGNORE_CASE) to VoiceCommand.Commit,
            // Space
            Regex("[\\s\u3000、。,.]?スペース$") to VoiceCommand.Space,
            Regex("[\\s\u3000、。,.]+space$", RegexOption.IGNORE_CASE) to VoiceCommand.Space,
            // Parentheses
            Regex("[\\s\u3000、。,.]?かっことじ$") to VoiceCommand.CloseParen, // must come before かっこ
            Regex("[\\s\u3000、。,.]?括弧閉じ$") to VoiceCommand.CloseParen,
            Regex("[\\s\u3000、。,.]+close\\s*paren(thesis)?$", RegexOption.IGNORE_CASE) to VoiceCommand.CloseParen,
            Regex("[\\s\u3000、。,.]?かっこ$") to VoiceCommand.OpenParen,
            Regex("[\\s\u3000、。,.]?括弧$") to VoiceCommand.OpenParen,
            Regex("[\\s\u3000、。,.]+open\\s*paren(thesis)?$", RegexOption.IGNORE_CASE) to VoiceCommand.OpenParen,
            Regex("[\\s\u3000、。,.]+parenthesis$", RegexOption.IGNORE_CASE) to VoiceCommand.OpenParen,
        )

        // ────────────────────────────────────────────────────
        //  Punctuation
        // ────────────────────────────────────────────────────

        /** Multi-char Japanese question endings (checked before single か). */
        private val JA_QUESTION_ENDINGS = listOf(
            "ですか", "ますか", "かな", "かね", "だろうか", "でしょうか"
        )

        /** Japanese sentence-ending forms that get 。 appended. */
        @Suppress("unused") // kept for documentation; actual check uses endsWith
        private val JA_SENTENCE_ENDINGS = listOf(
            "ます", "です", "ました", "でした", "ません",
            "だ", "た", "よ", "ね", "な", "わ", "ぞ", "ぜ",
            "しょう", "ましょう", "ください",
            "る", "く", "す", "つ", "む", "ぬ", "ぶ", "ぐ"
        )

        /** English question starters (lowercase, with trailing space). */
        private val EN_QUESTION_STARTERS = listOf(
            "what ", "how ", "why ", "when ", "where ", "who ", "which ",
            "is ", "are ", "was ", "were ", "do ", "does ", "did ",
            "can ", "could ", "will ", "would ", "should ", "shall ",
            "have ", "has ", "had "
        )

        /**
         * Japanese clause boundary patterns that get a 、 inserted after them.
         * Conservative: only matches unambiguous clause boundaries.
         * Avoids false positives on 「です」「ます」and 格助詞「が」.
         */
        private val JA_CLAUSE_BOUNDARY = Regex(
            // て/で-form: match "…て+continuation" or "…で+continuation"
            // Negative lookahead: exclude です/ですが/でした/でしょう (copula)
            "([\\p{InHiragana}\\p{InKatakana}\\p{InCJKUnifiedIdeographs}])" +
            "(て|で)" +
            "(?!す|した|しょう)" +  // Exclude です、でした、でしょう
            "(?=[\\p{InHiragana}\\p{InKatakana}\\p{InCJKUnifiedIdeographs}])" +
            "|" +
            // Longer, unambiguous particles: から、ので、けど、けれど、けれども、たら
            "([\\p{InHiragana}\\p{InKatakana}\\p{InCJKUnifiedIdeographs}])" +
            "(から|ので|けど|けれど|けれども|たら)" +
            "(?=[\\p{InHiragana}\\p{InKatakana}\\p{InCJKUnifiedIdeographs}])"
        )

        // ────────────────────────────────────────────────────
        //  Hallucination detection
        // ────────────────────────────────────────────────────

        /**
         * Known Whisper hallucination phrases.
         * If the entire chunk matches one of these (after trimming), return empty.
         */
        private val HALLUCINATION_EXACT: Set<String> = setOf(
            "ご視聴ありがとうございました",
            "ご視聴ありがとうございます",
            "ご清聴ありがとうございました",
            "ご清聴ありがとうございます",
            "チャンネル登録お願いします",
            "チャンネル登録よろしくお願いします",
            "高評価お願いします",
            "グッドボタンお願いします",
            "Thanks for watching",
            "Thank you for watching",
            "Subscribe to my channel",
            "Please subscribe",
            "Please like and subscribe",
        )

        /** Lowercased set for case-insensitive matching of English hallucinations. */
        private val HALLUCINATION_EXACT_LOWER: Set<String> =
            HALLUCINATION_EXACT.map { it.lowercase() }.toSet()

        /**
         * Pattern for detecting repeated phrases — a Whisper artifact.
         * Matches when a phrase of 4+ chars repeats 3+ times consecutively.
         */
        private val REPETITION_PATTERN = Regex("(.{4,}?)\\1{2,}")

        // ────────────────────────────────────────────────────
        //  Number normalization
        // ────────────────────────────────────────────────────

        /** Kanji digit mapping. */
        private val KANJI_DIGIT_MAP = mapOf(
            '零' to 0, '〇' to 0,
            '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5,
            '六' to 6, '七' to 7, '八' to 8, '九' to 9,
        )

        /** Kanji multiplier mapping. */
        private val KANJI_MULTIPLIER_MAP = mapOf(
            '十' to 10L, '百' to 100L, '千' to 1000L,
        )
        private val KANJI_BIG_MULTIPLIER_MAP = mapOf(
            '万' to 10_000L, '億' to 100_000_000L, '兆' to 1_000_000_000_000L,
        )

        /**
         * Regex matching a kanji number sequence.
         * Covers patterns like 三, 十, 百, 千, 万, 二千二十六, 三百五十, etc.
         */
        private val KANJI_NUMBER_PATTERN = Regex(
            "[零〇一二三四五六七八九十百千万億兆]+"
        )

        /**
         * Date pattern: 漢数字月漢数字日 → Arabic月Arabic日
         * e.g., 三月三十一日 → 3月31日
         */
        private val JA_DATE_PATTERN = Regex(
            "([零〇一二三四五六七八九十百千]+)月([零〇一二三四五六七八九十百千]+)日"
        )

        /**
         * Counter words that follow kanji numbers.
         * e.g., 三つ, 五個, 十人
         */
        private val JA_COUNTER_PATTERN = Regex(
            "([零〇一二三四五六七八九十百千万億兆]+)(つ|個|人|本|枚|台|匹|頭|冊|回|件|歳|才|年|月|日|時|分|秒|円|ドル|キロ|メートル|グラム|リットル)"
        )

        /**
         * Standalone kanji number pattern — numbers not followed by counter/月/日.
         * Used for general number conversion like にせんにじゅうろく → 2026.
         * Only converts sequences of 2+ kanji digits/multipliers to avoid
         * false positives on single-character words.
         */
        private val STANDALONE_KANJI_NUMBER = Regex(
            "([零〇一二三四五六七八九十百千万億兆]{2,})(?![月日つ個人本枚台匹頭冊回件歳才年時分秒円ドルキロメートルグラムリットル])"
        )

        // ────────────────────────────────────────────────────
        //  Text normalization
        // ────────────────────────────────────────────────────

        /** Multiple consecutive whitespace characters → single space. */
        private val MULTI_SPACE = Regex("[\\s\u3000]{2,}")

        /** Full-width digits ０-９ */
        private val FULLWIDTH_DIGIT_RANGE = '\uFF10'..'\uFF19'
        /** Full-width uppercase A-Z */
        private val FULLWIDTH_UPPER_RANGE = '\uFF21'..'\uFF3A'
        /** Full-width lowercase a-z */
        private val FULLWIDTH_LOWER_RANGE = '\uFF41'..'\uFF5A'

        // ────────────────────────────────────────────────────
        //  User filler learning threshold
        // ────────────────────────────────────────────────────

        /** Number of manual deletions required before a word is treated as a user filler. */
        const val USER_FILLER_THRESHOLD = 5
    }

    // ════════════════════════════════════════════════════
    //  Public API
    // ════════════════════════════════════════════════════

    /**
     * Main processing pipeline. Order:
     * 1. Detect hallucination (entire chunk)
     * 2. Detect voice command (exact match → return immediately)
     * 3. Detect trailing command
     * 4. Remove fillers
     * 5. Resolve self-corrections
     * 6. Normalize numbers/dates
     * 7. Normalize text (whitespace, full-width)
     * 8. Insert clause commas
     * 9. Insert terminal punctuation
     */
    fun process(text: String): ProcessResult {
        if (text.isBlank()) return ProcessResult(text = "")

        // 1. Hallucination check
        if (isHallucination(text)) return ProcessResult(text = "")

        // 2. Exact command match
        val exactCommand = detectVoiceCommand(text)
        if (exactCommand != null) {
            return ProcessResult(text = "", command = exactCommand)
        }

        // 3. Trailing command detection
        val trailing = detectTrailingCommand(text)
        val (textToProcess, command) = if (trailing != null) {
            trailing.first to trailing.second
        } else {
            text to null
        }

        // 4-9. Text processing pipeline
        var processed = removeFiller(textToProcess)
        processed = resolveCorrections(processed)
        processed = normalizeDates(processed)
        processed = normalizeCounters(processed)
        processed = normalizeStandaloneNumbers(processed)
        processed = normalizeText(processed)
        if (processed.isNotBlank()) {
            processed = insertClauseCommas(processed)
            processed = insertPunctuation(processed)
        }

        return ProcessResult(text = processed, command = command)
    }

    // ════════════════════════════════════════════════════
    //  Filler removal
    // ════════════════════════════════════════════════════

    // ════════════════════════════════════════════════════
    //  User filler auto-learning (Task 22b)
    // ════════════════════════════════════════════════════

    /**
     * Tracks how many times a word has been manually deleted after voice input.
     * When the count reaches [USER_FILLER_THRESHOLD], the word is treated as a filler.
     * Not thread-safe — access from the UI thread only.
     */
    private val userFillerCounts = mutableMapOf<String, Int>()

    /**
     * Record that the user manually deleted [word] after voice input.
     * Call this whenever the user deletes a word immediately after recognition.
     * Once a word accumulates [USER_FILLER_THRESHOLD] deletions it is treated as a filler.
     */
    fun recordFillerDeletion(word: String) {
        val count = (userFillerCounts[word] ?: 0) + 1
        userFillerCounts[word] = count
    }

    /** Returns the current deletion count for [word]. Useful for testing. */
    fun getFillerDeletionCount(word: String): Int = userFillerCounts[word] ?: 0

    /**
     * Remove filler words from text.
     * Japanese fillers support elongation (えーーーと, あのーーー, etc.)
     * あの/その are only removed when not followed by a demonstrative noun.
     * English fillers use word boundaries to avoid false positives.
     * User-learned fillers (deleted 5+ times) are also removed.
     */
    fun removeFiller(text: String): String {
        var result = text

        // Apply standard Japanese filler patterns (あの/その handled separately below)
        for (pattern in JA_FILLER_PATTERNS) {
            result = pattern.replace(result, "")
        }

        // Context-aware あの/その: only remove when NOT followed by a demonstrative noun
        result = ANO_SONO_FILLER.replace(result) { match ->
            val afterMatch = result.substring(match.range.last + 1)
            val isDemonstrative = DEMONSTRATIVE_NOUN_PREFIXES.any { afterMatch.startsWith(it) }
            if (isDemonstrative) match.value else ""
        }

        // English fillers
        for (pattern in EN_FILLER_PATTERNS) {
            result = pattern.replace(result, "")
        }

        // User-learned fillers (words deleted 5+ times)
        for ((word, count) in userFillerCounts) {
            if (count >= USER_FILLER_THRESHOLD && word.isNotEmpty()) {
                val escaped = Regex.escape(word)
                // Use \b for ASCII words, plain match for Japanese/CJK
                val isAscii = word.all { it.code < 128 }
                val pattern = if (isAscii) {
                    Regex("\\b$escaped\\b[,]?\\s?", RegexOption.IGNORE_CASE)
                } else {
                    Regex("$escaped[,]?[\\s\u3000]*")
                }
                result = pattern.replace(result, "")
            }
        }

        return result.trim()
    }

    // ════════════════════════════════════════════════════
    //  Self-correction
    // ════════════════════════════════════════════════════

    /**
     * Resolve self-corrections by keeping only the corrected part.
     * Handles multiple chained corrections: "A じゃなくて B じゃなくて C" → "C"
     * because patterns use greedy (.*) which consumes earlier corrections.
     * Also detects implicit rephrases via suffix overlap or near-identical phrases.
     */
    fun resolveCorrections(text: String): String {
        var result = text

        // Japanese corrections — greedy .* ensures last correction wins
        for (pattern in JA_CORRECTION_PATTERNS) {
            val match = pattern.find(result)
            if (match != null) {
                result = match.groupValues[2].trim()
                // Re-check all patterns on the remainder (handles nested corrections)
                return resolveCorrections(result)
            }
        }

        // English corrections
        for (pattern in EN_CORRECTION_PATTERNS) {
            val match = pattern.find(result)
            if (match != null) {
                result = match.groupValues[2].trim()
                return resolveCorrections(result)
            }
        }

        // Rephrase detection: implicit self-correction via suffix overlap or similarity
        result = resolveRephrases(result)

        return result
    }

    /**
     * Detect implicit rephrase patterns (Task 22c):
     * 1. Suffix matching: if the text contains a repeated suffix of 2+ chars where the
     *    two occurrences differ only in their prefix, keep the later one.
     *    e.g., "明日行きます明後日行きます" → "明後日行きます" (shared suffix "行きます")
     * 2. High similarity: consecutive delimited phrases that are nearly identical
     *    (edit distance ≤ threshold) → keep the later one.
     *
     * For unpunctuated continuous speech, suffix-overlap scanning is applied directly
     * on the full string. For punctuated text, segment-based comparison is used.
     */
    internal fun resolveRephrases(text: String): String {
        // First try segment-based detection (punctuated/spaced text)
        val segments = splitIntoSegments(text)
        if (segments.size >= 2) {
            val kept = mutableListOf<String>()
            var i = 0
            while (i < segments.size) {
                if (i + 1 < segments.size) {
                    val a = segments[i]
                    val b = segments[i + 1]
                    if (isRephrase(a, b)) {
                        i++ // skip a, process b next iteration
                        continue
                    }
                }
                kept.add(segments[i])
                i++
            }
            val joined = kept.joinToString("")
            if (joined != text) return joined
        }

        // Fall back to suffix-overlap scan on raw string (unpunctuated speech)
        return suffixOverlapScan(text)
    }

    /**
     * Scan the raw string for a suffix-overlap rephrase pattern without delimiters.
     * Finds the longest suffix S (2+ chars) that appears twice in [text] such that:
     *   - The first occurrence ends before the midpoint
     *   - The second occurrence ends at the end of the string
     *   - The prefix before the first S differs from the prefix before the second S
     * Returns the second phrase (from the start of the second prefix to end).
     *
     * Also applies near-identical detection on equal-length halves.
     */
    private fun suffixOverlapScan(text: String): String {
        val n = text.length
        if (n < 4) return text

        // Try all suffix lengths from longest possible to 2
        val maxSuffixLen = n / 2
        for (suffixLen in maxSuffixLen downTo 2) {
            val suffix = text.takeLast(suffixLen)
            // Find an earlier occurrence of this suffix in the first half
            val firstEnd = text.indexOf(suffix)
            if (firstEnd < 0) continue
            val firstStart = firstEnd - (n - suffixLen - firstEnd) // rough check
            // The first phrase ends at firstEnd + suffixLen - 1
            val firstPhraseEnd = firstEnd + suffixLen
            if (firstPhraseEnd >= n) continue // suffix is at the very end only
            // The second occurrence must end at n
            val secondStart = n - suffixLen
            // Prefix of first phrase: text[0..firstEnd)
            val prefixA = text.substring(0, firstEnd)
            // Prefix of second phrase: text[firstPhraseEnd..secondStart)
            val prefixB = text.substring(firstPhraseEnd, secondStart)
            if (prefixA == prefixB) continue // same prefix = not a correction
            if (prefixA.isEmpty() || prefixB.isEmpty()) continue
            // Looks like a rephrase: "prefixA + suffix + prefixB + suffix"
            // Keep the second phrase: prefixB + suffix
            return prefixB + suffix
        }

        // Near-identical halves check
        if (n % 2 == 0) {
            val half = n / 2
            val a = text.substring(0, half)
            val b = text.substring(half)
            if (isRephrase(a, b)) return b
        }

        return text
    }

    /**
     * Split text into natural segments using punctuation or whitespace.
     * Preserves delimiters attached to segments.
     */
    private fun splitIntoSegments(text: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch in "、。,. \t") {
                result.add(sb.toString())
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result.filter { it.isNotBlank() }
    }

    /**
     * Returns true if [b] appears to be a rephrase/correction of [a]:
     * - Common suffix of 2+ chars and differing prefix (suffix overlap rephrase)
     * - Nearly identical content (edit distance ≤ threshold for strings of length ≥ 4)
     */
    internal fun isRephrase(a: String, b: String): Boolean {
        val aTrim = a.trimEnd('、', '。', ',', '.', ' ')
        val bTrim = b.trimEnd('、', '。', ',', '.', ' ')
        if (aTrim.length < 2 || bTrim.length < 2) return false

        // 1. Suffix overlap: shared suffix of 2+ chars, differing prefix
        val minLen = minOf(aTrim.length, bTrim.length)
        var suffixLen = 0
        for (k in 1..minLen) {
            if (aTrim[aTrim.length - k] == bTrim[bTrim.length - k]) suffixLen++
            else break
        }
        if (suffixLen >= 2) {
            val prefixA = aTrim.dropLast(suffixLen)
            val prefixB = bTrim.dropLast(suffixLen)
            if (prefixA != prefixB && prefixA.isNotEmpty() && prefixB.isNotEmpty()) {
                return true
            }
        }

        // 2. Near-identical phrases (edit distance ≤ threshold for phrases of length ≥ 4)
        if (aTrim.length >= 4 && bTrim.length >= 4) {
            val dist = editDistance(aTrim, bTrim)
            val threshold = if (aTrim.length >= 8) 2 else 1
            if (dist in 1..threshold) return true
        }

        return false
    }

    /**
     * Standard Levenshtein edit distance between two strings.
     * Capped at [maxDist]+1 for efficiency — returns early if distance exceeds cap.
     */
    private fun editDistance(a: String, b: String, maxDist: Int = 3): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        if (kotlin.math.abs(a.length - b.length) > maxDist) return maxDist + 1

        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                curr[j] = if (a[i - 1] == b[j - 1]) {
                    prev[j - 1]
                } else {
                    1 + minOf(prev[j], curr[j - 1], prev[j - 1])
                }
            }
            prev.indices.forEach { prev[it] = curr[it] }
        }
        return curr[b.length]
    }

    // ════════════════════════════════════════════════════
    //  Punctuation
    // ════════════════════════════════════════════════════

    /**
     * Insert punctuation at the end of text based on content analysis.
     * - Japanese questions (ending with か, ですか, etc.) get ？
     * - English questions (starting with wh-words, aux verbs) get ?
     * - Japanese text gets 。
     * - English text gets .
     * Existing terminal punctuation is preserved.
     */
    fun insertPunctuation(text: String): String {
        if (text.isBlank()) return text
        val trimmed = text.trimEnd()

        // Already has terminal punctuation
        if (trimmed.last() in "。、？！.?!,;:\u3001\uFF01\uFF1F") return text

        // Japanese question endings (multi-char patterns first)
        for (ending in JA_QUESTION_ENDINGS) {
            if (trimmed.endsWith(ending)) {
                return "$trimmed？"
            }
        }

        // Single か check: preceding char must be hiragana/katakana/kanji
        if (trimmed.endsWith("か") && trimmed.length >= 2) {
            val preceding = trimmed[trimmed.length - 2]
            if (preceding.code in 0x3040..0x9FFF) {
                return "$trimmed？"
            }
        }

        // English question detection
        val lowerTrimmed = trimmed.lowercase()
        if (EN_QUESTION_STARTERS.any { lowerTrimmed.startsWith(it) }) {
            return "$trimmed?"
        }

        val hasJapanese = trimmed.any { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF }
        return if (hasJapanese) "$trimmed。" else "$trimmed."
    }

    /**
     * Insert commas (、) at natural clause boundaries in Japanese text.
     * Adds 、 after て-form, conditionals, reason clauses, and contrast clauses
     * when directly followed by another Japanese character.
     *
     * Only applies when no punctuation already exists at the boundary.
     */
    fun insertClauseCommas(text: String): String {
        // Only process Japanese text
        val hasJapanese = text.any { it.code in 0x3000..0x9FFF }
        if (!hasJapanese) return text

        return JA_CLAUSE_BOUNDARY.replace(text) { match ->
            // Two alternation branches: groups 1,2 (て/で) or groups 3,4 (longer particles)
            val preceding = match.groupValues[1].ifEmpty { match.groupValues[3] }
            val particle = match.groupValues[2].ifEmpty { match.groupValues[4] }
            "$preceding$particle、"
        }
    }

    // ════════════════════════════════════════════════════
    //  Voice commands
    // ════════════════════════════════════════════════════

    /**
     * Detect if the entire chunk is a voice command (exact match).
     */
    fun detectVoiceCommand(text: String): VoiceCommand? {
        val normalized = text.trim()
        val lower = normalized.lowercase()

        // Check lowercase first (covers English case-insensitivity)
        VOICE_COMMANDS_EXACT[lower]?.let { return it }
        // Also check original form for Japanese
        VOICE_COMMANDS_EXACT[normalized]?.let { return it }

        return null
    }

    /**
     * Detect voice command at the end of text, returning the command and
     * the remaining text with the command stripped.
     * Returns null if no command found at the end or if removing the command
     * would leave no text.
     */
    fun detectTrailingCommand(text: String): Pair<String, VoiceCommand>? {
        val trimmed = text.trim()
        for ((pattern, command) in VOICE_COMMAND_SUFFIXES) {
            val match = pattern.find(trimmed)
            if (match != null) {
                val remaining = trimmed.substring(0, match.range.first).trim()
                if (remaining.isNotEmpty()) {
                    return remaining to command
                }
            }
        }
        return null
    }

    // ════════════════════════════════════════════════════
    //  Hallucination detection
    // ════════════════════════════════════════════════════

    /**
     * Detect common Whisper hallucination patterns.
     * Returns true if the entire chunk appears to be a hallucination:
     * - Known phrases (ご視聴ありがとうございました, Thanks for watching, etc.)
     * - Excessive repetition of the same phrase
     */
    fun isHallucination(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        // Exact match (Japanese is case-sensitive; English is case-insensitive)
        if (trimmed in HALLUCINATION_EXACT) return true
        if (trimmed.lowercase() in HALLUCINATION_EXACT_LOWER) return true

        // Strip trailing punctuation for matching
        val stripped = trimmed.trimEnd('。', '！', '？', '.', '!', '?', '、', ',')
        if (stripped in HALLUCINATION_EXACT) return true
        if (stripped.lowercase() in HALLUCINATION_EXACT_LOWER) return true

        // Repetition detection: same phrase repeated 3+ times
        if (REPETITION_PATTERN.matches(trimmed)) return true

        // Single/short character repetition: same char repeated 10+ times
        if (trimmed.length >= 10 && trimmed.all { it == trimmed[0] }) return true

        // Short phrase repetition: 2-3 char patterns repeated 4+ times
        if (trimmed.length >= 8) {
            for (patLen in 2..3) {
                if (trimmed.length >= patLen * 4) {
                    val pat = trimmed.substring(0, patLen)
                    val repeats = trimmed.length / patLen
                    if (pat.repeat(repeats) == trimmed.take(repeats * patLen) && repeats >= 4) {
                        return true
                    }
                }
            }
        }

        return false
    }

    // ════════════════════════════════════════════════════
    //  Number / date normalization
    // ════════════════════════════════════════════════════

    /**
     * Convert a kanji number string to its numeric value.
     * Handles compound numbers like 二千二十六 → 2026, 三百五十 → 350.
     *
     * Algorithm:
     * - Process big multipliers (万/億/兆) as section separators
     * - Within each section, process 千/百/十 multipliers
     */
    fun kanjiToNumber(kanji: String): Long? {
        if (kanji.isEmpty()) return null

        // Handle simple positional notation: 〇, 一〇二 etc.
        if (kanji.all { KANJI_DIGIT_MAP.containsKey(it) }) {
            // Could be positional (like 二〇二六) or single digit
            if (kanji.length == 1) {
                return KANJI_DIGIT_MAP[kanji[0]]?.toLong()
            }
            // If it contains 〇 or is 2+ chars of only digits, treat as positional
            if (kanji.contains('〇') || kanji.contains('零')) {
                return kanji.map { KANJI_DIGIT_MAP[it] ?: return null }.fold(0L) { acc, d -> acc * 10 + d }
            }
        }

        var total = 0L
        var sectionTotal = 0L
        var current = 0L

        for (ch in kanji) {
            val digit = KANJI_DIGIT_MAP[ch]
            val mult = KANJI_MULTIPLIER_MAP[ch]
            val bigMult = KANJI_BIG_MULTIPLIER_MAP[ch]

            when {
                digit != null -> {
                    current = digit.toLong()
                }
                mult != null -> {
                    // 十, 百, 千: multiply current (default 1 if no preceding digit)
                    sectionTotal += (if (current == 0L) 1L else current) * mult
                    current = 0L
                }
                bigMult != null -> {
                    // 万, 億, 兆: multiply the accumulated section
                    sectionTotal += current
                    total += (if (sectionTotal == 0L) 1L else sectionTotal) * bigMult
                    sectionTotal = 0L
                    current = 0L
                }
                else -> return null // unknown character
            }
        }

        sectionTotal += current
        total += sectionTotal

        return total
    }

    /**
     * Normalize date expressions: 漢数字月漢数字日 → Arabic月Arabic日.
     * e.g., "三月三十一日" → "3月31日"
     */
    fun normalizeDates(text: String): String {
        return JA_DATE_PATTERN.replace(text) { match ->
            val month = kanjiToNumber(match.groupValues[1])
            val day = kanjiToNumber(match.groupValues[2])
            if (month != null && day != null) {
                "${month}月${day}日"
            } else {
                match.value // leave unchanged if parse fails
            }
        }
    }

    /**
     * Normalize counter expressions: 漢数字 + counter → Arabic + counter.
     * e.g., "三つ" → "3つ", "五個" → "5個", "十人" → "10人"
     */
    fun normalizeCounters(text: String): String {
        return JA_COUNTER_PATTERN.replace(text) { match ->
            val num = kanjiToNumber(match.groupValues[1])
            val counter = match.groupValues[2]
            if (num != null) {
                "$num$counter"
            } else {
                match.value
            }
        }
    }

    /**
     * Normalize standalone kanji numbers (not followed by counters or date markers).
     * Only converts sequences of 2+ kanji number characters to avoid false positives.
     */
    fun normalizeStandaloneNumbers(text: String): String {
        return STANDALONE_KANJI_NUMBER.replace(text) { match ->
            val num = kanjiToNumber(match.groupValues[1])
            num?.toString() ?: match.value
        }
    }

    // ════════════════════════════════════════════════════
    //  Text normalization
    // ════════════════════════════════════════════════════

    /**
     * Normalize whitespace and full-width/half-width characters.
     * - Multiple spaces → single space
     * - Full-width digits → half-width (０→0)
     * - Full-width letters → half-width (Ａ→A, ａ→a)
     * - Trim leading/trailing whitespace
     */
    fun normalizeText(text: String): String {
        var result = text.trim()
        result = MULTI_SPACE.replace(result, " ")
        result = buildString(result.length) {
            for (ch in result) {
                append(normalizeChar(ch))
            }
        }
        return result
    }

    /** Normalize a single character: full-width digits/letters → half-width. */
    private fun normalizeChar(ch: Char): Char = when (ch) {
        in FULLWIDTH_DIGIT_RANGE -> ('0' + (ch - '\uFF10'))
        in FULLWIDTH_UPPER_RANGE -> ('A' + (ch - '\uFF21'))
        in FULLWIDTH_LOWER_RANGE -> ('a' + (ch - '\uFF41'))
        else -> ch
    }
}
