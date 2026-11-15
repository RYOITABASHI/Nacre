package space.manus.nacre.ai

import android.content.Context

/**
 * Which local LLM the voice refinement uses, chosen by the user in
 * Settings → AI Models. Qwen 2.5 1.5B is the default (auto-provisioned);
 * Qwen3 1.7B is an opt-in upgrade that is only ever downloaded explicitly
 * (about 1.1GB) and is used only when downloaded AND selected here —
 * [ModelDownloader.getPreferredLlmModelPaths] puts the selection first
 * and keeps Qwen 2.5 as the automatic fallback. Read/written via the
 * same prefs file as [RefinePresets] (the refinement feature area).
 */
object RefineModel {
    const val ID_QWEN25 = "qwen25"
    const val ID_QWEN3 = "qwen3"

    private const val PREFS = "nacre_refine"
    private const val KEY_MODEL = "refine_model_id"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun selectedId(ctx: Context): String =
        prefs(ctx).getString(KEY_MODEL, ID_QWEN25) ?: ID_QWEN25

    fun setSelectedId(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_MODEL, id).apply()
    }

    fun isQwen3Selected(ctx: Context): Boolean = selectedId(ctx) == ID_QWEN3
}
