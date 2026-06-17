package space.manus.nacre.ai

/**
 * Post-processes raw voice transcription.
 *
 * Two-stage processing:
 * 1. quickClean() — instant rule-based cleanup (spaces, punctuation, fillers)
 * 2. LLM refinement — handled by VoiceInputManager via in-process LlmService (AIDL).
 *    Callers pass [DICTATION_CLEANUP_INSTRUCTION] as the instruction argument.
 */
object LlmPostProcessor {

    /**
     * System prompt for voice-to-text cleanup. Passed as the `instruction` to
     * ILlmService.transform() — LlmService wraps it in the Qwen ChatML system turn.
     */
    const val DICTATION_CLEANUP_INSTRUCTION =
        "あなたは日本語の音声認識結果を自然な書き言葉に整える編集者です。\n" +
        "以下の規則に従って入力を整形してください:\n" +
        "- フィラー（えーっと、あのー、まあ、そのー、など）を削除\n" +
        "- 言い直しがあれば後の発話を採用\n" +
        "- 助詞の欠落や誤りを文脈から修正\n" +
        "- 同音異義語の誤認識を文脈から正しい語に修正\n" +
        "- 不要な繰り返しを整理\n" +
        "- 句読点（、。）を適切に挿入\n" +
        "- 疑問文は文末を「？」にする\n" +
        "- 意味や話者の意図は一切変更しない\n" +
        "出力は整形後のテキストのみ。前置き・説明・引用符は不要。"

    // Fillers sorted longest-first to avoid partial matches
    // Note: single-char "ん" removed — too aggressive, breaks "なん", "んです" etc.
    private val FILLERS = listOf(
        "えーっと", "えっと", "えーと", "あのー", "そのー", "ええと",
        "えー", "あー", "うーん", "あの", "まあ", "なんか", "こう",
        "その", "うん",
    ).sortedByDescending { it.length }

    // Technical term dictionary (katakana → proper notation)
    private val TECH_TERMS = mapOf(
        "エーピーアイ" to "API", "エルエルエム" to "LLM",
        "ジーピーティー" to "GPT", "チャットジーピーティー" to "ChatGPT",
        "ピーティーワイ" to "PTY", "ジェイソン" to "JSON",
        "エイチティーティーピー" to "HTTP", "エイチティーティーピーエス" to "HTTPS",
        "ユーアールエル" to "URL", "エスキューエル" to "SQL",
        "ギットハブ" to "GitHub", "ギット" to "Git", "ぎっと" to "Git", "ジット" to "Git",
        "タイプスクリプト" to "TypeScript", "ジャバスクリプト" to "JavaScript",
        "コトリン" to "Kotlin", "リアクト" to "React", "アンドロイド" to "Android",
        "グラドル" to "Gradle", "グレードル" to "Gradle", "コンポーズ" to "Compose",
        "エーピーケー" to "APK", "えーあい" to "AI",
        "ウィスパー" to "Whisper", "シェルパ" to "sherpa",
        "センスボイス" to "SenseVoice", "タイプレス" to "Typeless",
        "コーデックス" to "Codex", "シェリー" to "Shelly", "エムシーピー" to "MCP",
        "エーディービー" to "ADB", "ログキャット" to "logcat",
        "ターミナル" to "ターミナル", // keep as-is (prevent partial match issues)
    ).entries.sortedByDescending { it.key.length }

    private data class RegexFix(val pattern: Regex, val replacement: String)

    private val CODING_FIXES = listOf(
        RegexFix(Regex("^(?:一|いち|1)リバース(して|しといて|お願いします)?([。！!]?)$"), "git rebase$1$2"),
        RegexFix(Regex("^(?:ぎっと|ギット|ジット|Git|git|get|Get)\\s*リバース(?=(して|しといて|お願いします)?[。！!\\s]*$)"), "git rebase"),
        RegexFix(Regex("^(?:ぎっと|ギット|ジット|Git|git|get|Get)\\s*リベース(?=(して|しといて|お願いします)?[。！!\\s]*$)"), "git rebase"),
        RegexFix(Regex("(?:エヌ[？?、\\s]*)?ピーエム\\s*インストール\\s*"), "npm install "),
        RegexFix(Regex("エヌ[？?、\\s]*ピーエム\\s*インストール\\s*"), "npm install "),
        RegexFix(Regex("(?i)\\bNPM\\b\\s*インストール\\s*"), "npm install "),
        RegexFix(Regex("エヌ[？?、\\s]*ピーエム"), "npm"),
        RegexFix(Regex("(?i)\\bNPM\\b"), "npm"),
    )

    // Common SenseVoice misrecognition dictionary (wrong → correct)
    private val MISRECOGNITION_FIXES = mapOf(
        "制度駆動点。" to "精度、句読点、",  // include trailing period → comma
        "制度駆動点" to "精度、句読点",
        "制度？当点" to "精度、句読点、",
        "制度?当点" to "精度、句読点、",
        "制度句" to "精度、句読点、",
        "当点" to "句読点",
        "補正？要約" to "補正、要約",
        "補正?要約" to "補正、要約",
        "繁映" to "反映",
        "繁栄" to "反映",  // context: テキスト繁栄→テキスト反映
        "精入力" to "音声入力",
        "レレム" to "LLM",
        "タイムレス" to "Typeless",
    ).entries.sortedByDescending { it.key.length }

    // False sentence break: 「。」 after particles/conjunctive forms (pause ≠ sentence end)
    // Pattern: particle/suffix + 「。」 + continuation (not end of string)
    private val FALSE_PERIOD_AFTER_PARTICLE = Regex(
        "(けれど|けど|ので|のに|ながら|ところ|だけど|だと|って|つつ|から|まで|より|[がはをにでともへてしば])。(?!$)"
    )

    // Also catch: 「。」 before continuation particles (original logic)
    private val CONTINUATION_PARTICLES = Regex(
        "。([がはをにでともへ]|より|から|まで|けど|けれど|ので|のに|たら|ば|て|ながら|つつ|し|ところ|って)"
    )

    // Question-ending patterns (conservative: only clear question forms)
    // Removed: よね (often assertion 「きついよね。」), だろう (can be assertion)
    // なの/ないの only at absolute end to avoid false positives
    private val QUESTION_ENDINGS = Regex(
        "(んですか|ませんか|でしょうか|ですか|ますか|じゃないですか|のですか|のか)。$"
    )

    /**
     * Instant rule-based cleanup. No network, no delay.
     *
     * Processing order:
     * A. Artifact removal (leading ？, fillers)
     * B. Technical term dictionary
     * C. Half-width space removal (Japanese context)
     * D. Mid-sentence 「。」 fix (before continuation particles)
     * E. Duplicate phrase removal
     * F. Punctuation cleanup
     * G. Question detection
     */
    fun quickClean(rawText: String): String {
        if (rawText.isBlank()) return rawText
        var text = rawText

        // A. Remove leading/trailing ？ artifacts
        text = text.trimStart('？', '?')

        // A. Remove fillers
        for (filler in FILLERS) {
            text = text.replace(filler, "")
        }

        // B. Misrecognition dictionary (before tech terms, as some overlap)
        for ((wrong, correct) in MISRECOGNITION_FIXES) {
            text = text.replace(wrong, correct)
        }

        // B2. Coding-command normalization for common STT failures observed in logs.
        for ((pattern, replacement) in CODING_FIXES) {
            text = text.replace(pattern, replacement)
        }

        // B. Technical term dictionary
        for ((kana, term) in TECH_TERMS) {
            text = text.replace(kana, term)
        }

        // C. Remove half-width spaces between Japanese/CJK characters
        text = text.replace(
            Regex("([\\u3000-\\u9FFF\\uF900-\\uFAFF\\uFF00-\\uFFEF])\\s+([\\u3000-\\u9FFF\\uF900-\\uFAFF\\uFF00-\\uFFEF])"),
            "$1$2"
        )

        // D. Remove false mid-sentence 「。」 before continuation particles
        text = text.replace(CONTINUATION_PARTICLES, "$1")

        // D2. Remove false 「。」 after particles/conjunctive endings (SenseVoice pause artifacts)
        // e.g. 「けど。ラグが。大きくなる」→「けど、ラグが大きくなる」
        text = text.replace(FALSE_PERIOD_AFTER_PARTICLE) { match ->
            val particle = match.groupValues[1]
            // After けど/けれど, replace with comma; after other particles, just remove
            if (particle == "けど" || particle == "けれど" || particle == "だけど") {
                "$particle、"
            } else {
                particle
            }
        }

        // E. Duplicate phrase removal (immediate repetition of 2-20 char phrases)
        text = text.replace(Regex("(.{2,20})\\1"), "$1")

        // E2. Duplicate with intervening 「。」 (e.g. 「音声入力テスト中音声入力テスト中。」)
        text = text.replace(Regex("(.{4,20})[。、]?\\1"), "$1")

        // F. Punctuation cleanup
        text = text.replace("。。", "。")
            .replace("、、", "、")
            .replace("？？", "？")
            .replace("。？", "？")
            .replace("。、", "、")

        // F. Collapse multiple spaces
        text = text.replace(Regex(" +"), " ")

        // F. Remove leading punctuation artifacts
        text = text.trimStart('。', '、', ' ').trim()

        // G. Fix false 「？」 after listing particles (「や？」→「や、」)
        text = text.replace(Regex("([やとか])？(?!$)"), "$1、")

        // G. Question detection: 「ですか。」→「ですか？」
        text = text.replace(QUESTION_ENDINGS, "$1？")

        // G. Ensure question sentences with interrogative words end with ？
        // Only match when interrogative word is within last 15 chars (near sentence end = likely question)
        text = text.replace(
            Regex("((?:何|どう|いつ|どこ|どれ|なぜ|なんで|どうして)[^。？]{0,12})。$"),
            "$1？"
        )

        writeDiag("quickClean: '${rawText.take(60)}' → '${text.take(60)}'")
        return text
    }

    private fun writeDiag(message: String) {
        try {
            val file = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                "nacre-llm-debug.txt"
            )
            val ts = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            file.appendText("[$ts] $message\n")
        } catch (_: Exception) {}
    }
}
