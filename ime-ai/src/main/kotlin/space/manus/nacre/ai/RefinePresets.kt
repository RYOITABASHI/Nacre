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
    private const val KEY_AUTO_BY_APP = "auto_by_app"
    private const val KEY_CATEGORY_PREFIX = "cat_preset_"
    private const val DEFAULT_ID = "clean"

    /**
     * Generic per-category defaults for the auto (per-app) mode: which
     * preset a category of app gets until the user remaps it in Settings.
     * Keys are VoiceAppCategory names (ime-core); kept as plain strings
     * here so this module needs no dependency on the resolver.
     */
    val DEFAULT_CATEGORY_PRESETS: Map<String, String> = mapOf(
        "MAIL" to "keigo",
        "CHAT" to "clean",
        "BROWSER" to "clean",
        "CODE" to "clean",
        "NOTES" to "bullets",
        "OTHER" to "clean",
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun activeId(ctx: Context): String = prefs(ctx).getString(KEY_ACTIVE, DEFAULT_ID) ?: DEFAULT_ID

    fun setActiveId(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_ACTIVE, id).apply()
    }

    /** Per-app tone: when true, sessions resolve their preset from the
     *  foreground app's category mapping instead of the manual choice. */
    fun autoByAppEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_BY_APP, false)

    fun setAutoByAppEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_BY_APP, enabled).apply()
    }

    /** Preset id mapped to an app category (generic category name, e.g.
     *  "MAIL"), falling back to [DEFAULT_CATEGORY_PRESETS]. */
    fun categoryPresetId(ctx: Context, categoryName: String): String =
        prefs(ctx).getString(KEY_CATEGORY_PREFIX + categoryName, null)
            ?: DEFAULT_CATEGORY_PRESETS[categoryName]
            ?: DEFAULT_ID

    fun setCategoryPresetId(ctx: Context, categoryName: String, presetId: String) {
        prefs(ctx).edit().putString(KEY_CATEGORY_PREFIX + categoryName, presetId).apply()
    }

    fun presetById(id: String): Preset = PRESETS.firstOrNull { it.id == id } ?: PRESETS.first()

    fun active(ctx: Context): Preset =
        PRESETS.firstOrNull { it.id == activeId(ctx) } ?: PRESETS.first()

    fun activeInstruction(ctx: Context): String = active(ctx).instruction
}
