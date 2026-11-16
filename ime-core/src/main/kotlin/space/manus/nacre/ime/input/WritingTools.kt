package space.manus.nacre.ime.input

/**
 * AI Writing Tools: local-LLM rewrite operations (校正 / 丁寧に / 簡潔に /
 * 要約 / 箇条書き / 言い換え) over text already in the editor — the
 * selection when there is one, otherwise the paragraph before the cursor.
 *
 * Pure Kotlin (no Android dependencies) so target extraction, prompt
 * construction and result cleanup are JVM-testable. The Android-side
 * contract enforced by the call sites (CommandPalette / WritingToolsPanel
 * / VoiceInputManager):
 *  - password fields never reach this code at all — no section, no
 *    extraction (SPEC: no AI features in password fields);
 *  - the text is sent ONLY to the on-device LlmService (Qwen 2.5 / Qwen3);
 *    the cloud refiner chain is never used here;
 *  - a result is applied ONLY after the panel's explicit 適用 tap, and
 *    ONLY when the editor still holds exactly the captured target
 *    (selection: [VoiceEditPolicy.isSelectionIntact]; paragraph:
 *    [paragraphIntact]) — otherwise the original stays untouched.
 */
object WritingTools {

    /**
     * Upper bound for one operation's target. Keeps the prompt and the
     * 512-token output budget of LlmService in a range the small local
     * models handle reliably, and bounds the apply-time re-read.
     */
    const val MAX_TARGET_CHARS = 1000

    enum class Op(val id: String, val label: String, val keywords: String) {
        PROOFREAD("proofread", "校正", "proofread 校正 誤字 脱字 チェック"),
        POLITE("polite", "丁寧に", "polite 丁寧 敬語 keigo ですます"),
        SHORTEN("shorten", "簡潔に", "shorten concise 簡潔 短く"),
        SUMMARIZE("summarize", "要約", "summarize summary 要約 まとめ"),
        BULLETS("bullets", "箇条書き", "bullets list 箇条書き リスト"),
        REPHRASE("rephrase", "言い換え", "rephrase rewrite 言い換え 書き換え"),
    }

    /** Style of the 丁寧に operation, preselected from the per-app tone. */
    enum class PoliteStyle { DESU_MASU, KEIGO }

    /**
     * What one run rewrites. [Paragraph] is guaranteed to be an exact
     * suffix of the text before the cursor at capture time, which is
     * what makes the apply-time verification (and the delete-by-length
     * replacement) exact.
     */
    sealed interface Target {
        val text: String

        data class Selection(override val text: String) : Target
        data class Paragraph(override val text: String) : Target
    }

    sealed interface TargetResolution {
        data class Found(val target: Target) : TargetResolution
        data object None : TargetResolution

        /** A selection exists but exceeds [MAX_TARGET_CHARS]; replacing
         *  only part of a selection would corrupt it, so the operation
         *  is refused with an explanation instead of truncating. */
        data object SelectionTooLong : TargetResolution
    }

    enum class ModelAvailability { READY, NEEDS_LOAD, NO_MODEL }

    /**
     * Target rule: a non-empty selection wins. Without a selection, the
     * target is the paragraph before the cursor — see [extractParagraph].
     */
    fun resolveTarget(selection: String?, textBeforeCursor: String?): TargetResolution {
        if (!selection.isNullOrEmpty()) {
            return if (selection.length > MAX_TARGET_CHARS) {
                TargetResolution.SelectionTooLong
            } else {
                TargetResolution.Found(Target.Selection(selection))
            }
        }
        val paragraph = extractParagraph(textBeforeCursor)
            ?: return TargetResolution.None
        return TargetResolution.Found(Target.Paragraph(paragraph))
    }

    /**
     * The paragraph before the cursor: the text after the last blank
     * line (a line containing only spaces / tabs / ideographic spaces),
     * or the whole [textBeforeCursor] when there is no blank line.
     * Leading whitespace is stripped and an over-long paragraph is cut
     * to its last [MAX_TARGET_CHARS] characters — both operations keep
     * the result an exact suffix of the input, which the apply path
     * relies on. Null when nothing but whitespace precedes the cursor.
     */
    fun extractParagraph(textBeforeCursor: String?): String? {
        if (textBeforeCursor.isNullOrEmpty()) return null
        val raw = textBeforeCursor
        var start = 0
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '\n') {
                // Blank line ahead? Only whitespace until the next '\n'
                // (or the end of the text, i.e. cursor after a blank line).
                var j = i + 1
                while (j < raw.length &&
                    (raw[j] == ' ' || raw[j] == '\t' || raw[j] == '　' || raw[j] == '\r')
                ) {
                    j++
                }
                if (j < raw.length && raw[j] == '\n') {
                    start = j + 1
                    i = j + 1
                    continue
                }
                if (j == raw.length) {
                    start = raw.length
                    break
                }
            }
            i++
        }
        var paragraph = raw.substring(start)
        paragraph = paragraph.trimStart(' ', '\t', '　', '\n', '\r')
        if (paragraph.isBlank()) return null
        if (paragraph.length > MAX_TARGET_CHARS) {
            paragraph = paragraph.takeLast(MAX_TARGET_CHARS)
            // Never start mid–surrogate pair: a leading low surrogate is
            // the tail of a character that lies outside the target.
            // Dropping it keeps the result an exact suffix of the input.
            if (paragraph.isNotEmpty() && paragraph[0].isLowSurrogate()) {
                paragraph = paragraph.substring(1)
            }
            if (paragraph.isBlank()) return null
        }
        return paragraph
    }

    /**
     * True when an editor selection range is collapsed (a plain cursor,
     * no selected text). Paragraph replacement requires this: with an
     * active selection, commitText would replace the SELECTED text —
     * which the paragraph flow never captured — in addition to the
     * paragraph it deletes.
     */
    fun isCollapsedSelection(selectionStart: Int, selectionEnd: Int): Boolean =
        selectionStart >= 0 && selectionStart == selectionEnd

    /**
     * Apply-time guard for paragraph targets: the text before the cursor
     * must still end with exactly the captured paragraph. If the user
     * typed or deleted anything in the meantime, the apply is aborted
     * and the editor text stays untouched.
     */
    fun paragraphIntact(textBeforeCursorNow: String?, original: String): Boolean =
        textBeforeCursorNow != null &&
            textBeforeCursorNow.length >= original.length &&
            textBeforeCursorNow.endsWith(original)

    /**
     * The 丁寧に style follows the per-app voice tone (PR #56): when the
     * preset resolved for the foreground app is the keigo rewrite preset
     * (RefinePresets id "keigo" — e.g. the MAIL category default), 丁寧に
     * produces business keigo; otherwise plain です・ます.
     */
    fun politeStyleFor(resolvedPresetId: String?): PoliteStyle =
        if (resolvedPresetId == "keigo") PoliteStyle.KEIGO else PoliteStyle.DESU_MASU

    /**
     * One instruction per operation, in the house style of
     * LlmPostProcessor / RefinePresets: Japanese, and the model must
     * return ONLY the rewritten text (no preamble, no quotes, no code
     * fences). Passed as the `instruction` of ILlmService.transform(),
     * which wraps it in the model's system turn.
     */
    fun instructionFor(op: Op, politeStyle: PoliteStyle = PoliteStyle.DESU_MASU): String =
        when (op) {
            Op.PROOFREAD ->
                "あなたは日本語の校正者です。入力された文章の誤字脱字、助詞の誤り、" +
                    "句読点の誤りのみを修正してください。意味・内容・語調・文体は変えず、" +
                    "誤りがない場合はそのまま返してください。" +
                    "出力は校正後のテキストのみ。前置き・説明・引用符・コードブロックは付けない。"
            Op.POLITE -> when (politeStyle) {
                PoliteStyle.DESU_MASU ->
                    "次の文を、意味を変えずに丁寧な「です・ます」調に整えてください。" +
                        "出力は整えたテキストのみ。前置き・説明・引用符・コードブロックは付けない。"
                PoliteStyle.KEIGO ->
                    "次の文を、意味を変えずに丁寧なビジネス敬語に整えてください。" +
                        "出力は整えたテキストのみ。前置き・説明・引用符・コードブロックは付けない。"
            }
            Op.SHORTEN ->
                "次の文を、意味と要点を保ったまま、冗長な表現を削って簡潔に書き直してください。" +
                    "出力は書き直したテキストのみ。前置き・説明・引用符・コードブロックは付けない。"
            Op.SUMMARIZE ->
                "次の文章を、要点を保ったまま簡潔な日本語に要約してください。" +
                    "箇条書きにはせず、文章の形で要約すること。" +
                    "出力は要約のテキストのみ。前置き・説明・引用符・コードブロックは付けない。"
            Op.BULLETS ->
                "次の文章を、要点ごとに日本語の箇条書き（・で始まる行）に整理してください。" +
                    "出力は箇条書きのみ。前置き・説明・引用符・コードブロックは付けない。"
            Op.REPHRASE ->
                "次の文を、意味と語調を保ったまま、別の自然な言い回しに書き換えてください。" +
                    "出力は書き換えたテキストのみ。前置き・説明・引用符・コードブロックは付けない。"
        }

    /**
     * Defensive cleanup of a raw model result before it is shown (and
     * later applied): the prompts already demand bare text, but small
     * local models still sometimes wrap the answer in a code fence,
     * quotes, or a label line ("校正結果："). Only whole-output wrappers
     * are stripped — the body itself is never edited. Null when nothing
     * usable remains. Repetition loops are rejected earlier, in
     * VoiceInputManager.transformForWriting (LlmPostProcessor guard).
     */
    fun cleanResult(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null

        // Markdown code fence wrapping the whole output.
        if (text.startsWith("```")) {
            val firstNewline = text.indexOf('\n')
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1).removeSuffix("```").trim()
            }
        }

        // Label-style preamble line ("校正結果：" etc.): a short first
        // line ending in a colon, containing no sentence punctuation,
        // followed by the actual body.
        val lines = text.lines()
        if (lines.size >= 2) {
            val first = lines[0].trim()
            if (first.length <= 20 &&
                (first.endsWith("：") || first.endsWith(":")) &&
                !first.contains("。") && !first.contains("・")
            ) {
                text = lines.drop(1).joinToString("\n").trim()
            }
        }

        // Quotes wrapping the ENTIRE output.
        val quotePairs = listOf(
            '「' to '」', '『' to '』', '"' to '"', '“' to '”',
        )
        for ((open, close) in quotePairs) {
            if (text.length >= 2 && text.first() == open && text.last() == close) {
                text = text.substring(1, text.length - 1).trim()
                break
            }
        }

        return text.ifEmpty { null }
    }
}
