package space.manus.nacre.ime.input

/**
 * Per-app voice tone: classify the foreground app into a generic category
 * and resolve which refinement preset a voice session should use.
 *
 * Pure Kotlin (no Android dependencies) so the rules are JVM-testable.
 * Categories and keyword rules are GENERIC (mail / chat / browser / code /
 * notes) — they describe app kinds, never a specific user's app list; the
 * category → preset mapping itself lives in user settings (RefinePresets).
 */
enum class VoiceAppCategory { MAIL, CHAT, BROWSER, CODE, NOTES, OTHER }

object VoiceToneResolver {

    /**
     * Ordered keyword rules matched against the lowercase package name.
     * Order matters: mail before chat (mail clients often contain "msg"),
     * code before browser (editors sometimes embed "web"), notes last
     * among the specific kinds because "note" is a weak signal.
     */
    private val RULES: List<Pair<VoiceAppCategory, List<String>>> = listOf(
        VoiceAppCategory.MAIL to listOf(
            "mail", "gmail", "gm", "outlook", "protonmail", "k9", "inbox", "yahoo",
        ),
        VoiceAppCategory.CHAT to listOf(
            "whatsapp", "messenger", "telegram", "discord", "slack", "signal",
            "securesms", "line", "kakao", "wechat", "skype", "viber", "chat",
            "messaging", "messages", "sms", "mms",
        ),
        VoiceAppCategory.CODE to listOf(
            "termux", "terminal", "userland", "connectbot", "ssh", "git",
            "github", "code", "studio", "editor", "ide",
        ),
        VoiceAppCategory.BROWSER to listOf(
            "chrome", "firefox", "browser", "brave", "opera", "vivaldi",
            "duckduckgo", "sbrowser",
        ),
        VoiceAppCategory.NOTES to listOf(
            "notes", "note", "keep", "notion", "evernote", "obsidian",
            "memo", "joplin", "onenote",
        ),
    )

    fun categorize(packageName: String?): VoiceAppCategory {
        if (packageName.isNullOrBlank()) return VoiceAppCategory.OTHER
        val pkg = packageName.lowercase()
        // Segment-aware match against the dot-separated package segments.
        // Short keywords (<=4 chars) must equal a whole segment so innocent
        // packages don't match by substring ("digital" must not hit "git",
        // "online" must not hit "line"); longer keywords may also match as
        // a substring of a segment ("texteditor" hits "editor").
        val segments = pkg.split('.')
        for ((category, keywords) in RULES) {
            for (keyword in keywords) {
                val hit = segments.any { segment ->
                    segment == keyword || (keyword.length >= 5 && segment.contains(keyword))
                }
                if (hit) return category
            }
        }
        return VoiceAppCategory.OTHER
    }

    data class ResolvedPreset(
        val presetId: String,
        /** True when the preset came from the per-app auto mapping. */
        val autoChosen: Boolean,
    )

    /**
     * Preset precedence for one voice session:
     *   1. an explicit per-session manual choice ([sessionOverrideId]) —
     *      always wins, in both manual and auto mode;
     *   2. auto mode: the category mapping for the foreground app
     *      (falling back to the manual preset when unmapped);
     *   3. manual mode: the user's standing manual preset.
     */
    fun resolvePreset(
        sessionOverrideId: String?,
        autoEnabled: Boolean,
        manualId: String,
        category: VoiceAppCategory,
        autoMapping: Map<VoiceAppCategory, String>,
    ): ResolvedPreset {
        if (!sessionOverrideId.isNullOrBlank()) {
            return ResolvedPreset(sessionOverrideId, autoChosen = false)
        }
        if (autoEnabled) {
            val mapped = autoMapping[category]
            if (!mapped.isNullOrBlank()) {
                return ResolvedPreset(mapped, autoChosen = true)
            }
        }
        return ResolvedPreset(manualId, autoChosen = false)
    }
}
