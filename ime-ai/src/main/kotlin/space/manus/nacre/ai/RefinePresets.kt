package space.manus.nacre.ai

import android.content.Context

/**
 * Selectable voice rewrite modes. The active preset's instruction is applied by
 * the refinement LLM (cloud or local) instead of the default cleanup — so the
 * user can dictate and get a translation, summary, formal rewrite, etc.
 * Default is plain cleanup ("整文"). Read/written in the main process.
 */
object RefinePresets {
    data class Preset(val id: String, val label: String, val instruction: String)

    val PRESETS: List<Preset> = listOf(
        Preset("clean", "整文", LlmPostProcessor.DICTATION_CLEANUP_INSTRUCTION),
        Preset(
            "en",
            "英訳",
            "次の日本語を自然で正確な英語に翻訳してください。訳文のテキストのみを出力し、説明・引用符・原文は付けない。",
        ),
        Preset(
            "summary",
            "要約",
            "次の発話内容を、要点を保ったまま簡潔な日本語に要約してください。要約のテキストのみを出力する。",
        ),
        Preset(
            "keigo",
            "敬語",
            "次の文を、意味を変えずに丁寧なビジネス敬語に整えてください。本文のみを出力し、説明は付けない。",
        ),
        Preset(
            "bullets",
            "箇条書き",
            "次の発話を、要点ごとに日本語の箇条書き（・で始まる行）に整理してください。箇条書きのみを出力する。",
        ),
    )

    private const val PREFS = "nacre_refine"
    private const val KEY_ACTIVE = "active_preset"
    private const val DEFAULT_ID = "clean"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun activeId(ctx: Context): String = prefs(ctx).getString(KEY_ACTIVE, DEFAULT_ID) ?: DEFAULT_ID

    fun setActiveId(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun active(ctx: Context): Preset =
        PRESETS.firstOrNull { it.id == activeId(ctx) } ?: PRESETS.first()

    fun activeInstruction(ctx: Context): String = active(ctx).instruction
}
