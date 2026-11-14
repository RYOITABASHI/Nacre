package space.manus.nacre.ime.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [VoiceToneResolver]: generic app categorization and the
 * per-session preset precedence (session override > auto mapping > manual).
 */
class VoiceToneResolverTest {

    @Test
    fun `mail apps categorize as MAIL`() {
        assertEquals(VoiceAppCategory.MAIL, VoiceToneResolver.categorize("com.google.android.gm"))
        assertEquals(VoiceAppCategory.MAIL, VoiceToneResolver.categorize("com.microsoft.office.outlook"))
        assertEquals(VoiceAppCategory.MAIL, VoiceToneResolver.categorize("com.fsck.k9"))
    }

    @Test
    fun `chat apps categorize as CHAT`() {
        assertEquals(VoiceAppCategory.CHAT, VoiceToneResolver.categorize("jp.naver.line.android"))
        assertEquals(VoiceAppCategory.CHAT, VoiceToneResolver.categorize("com.whatsapp"))
        assertEquals(VoiceAppCategory.CHAT, VoiceToneResolver.categorize("org.telegram.messenger"))
        assertEquals(VoiceAppCategory.CHAT, VoiceToneResolver.categorize("org.thoughtcrime.securesms"))
        assertEquals(VoiceAppCategory.CHAT, VoiceToneResolver.categorize("com.google.android.apps.messaging"))
    }

    @Test
    fun `browser code and notes categorize`() {
        assertEquals(VoiceAppCategory.BROWSER, VoiceToneResolver.categorize("com.android.chrome"))
        assertEquals(VoiceAppCategory.BROWSER, VoiceToneResolver.categorize("com.sec.android.app.sbrowser"))
        assertEquals(VoiceAppCategory.CODE, VoiceToneResolver.categorize("com.termux"))
        assertEquals(VoiceAppCategory.CODE, VoiceToneResolver.categorize("com.github.android"))
        assertEquals(VoiceAppCategory.NOTES, VoiceToneResolver.categorize("com.google.android.keep"))
        assertEquals(VoiceAppCategory.NOTES, VoiceToneResolver.categorize("md.obsidian"))
    }

    @Test
    fun `unknown and null packages are OTHER`() {
        assertEquals(VoiceAppCategory.OTHER, VoiceToneResolver.categorize("com.example.unknownapp"))
        assertEquals(VoiceAppCategory.OTHER, VoiceToneResolver.categorize(null))
        assertEquals(VoiceAppCategory.OTHER, VoiceToneResolver.categorize(""))
    }

    @Test
    fun `short keywords do not substring-match innocent packages`() {
        // "digital" contains "git", "online" contains "line" — neither may hit.
        assertEquals(VoiceAppCategory.OTHER, VoiceToneResolver.categorize("com.digital.wallet"))
        assertEquals(VoiceAppCategory.OTHER, VoiceToneResolver.categorize("com.onlinebank.app"))
    }

    private val mapping = mapOf(
        VoiceAppCategory.MAIL to "keigo",
        VoiceAppCategory.CHAT to "clean",
        VoiceAppCategory.NOTES to "bullets",
    )

    @Test
    fun `session override wins over auto and manual`() {
        val r = VoiceToneResolver.resolvePreset(
            sessionOverrideId = "summary",
            autoEnabled = true,
            manualId = "clean",
            category = VoiceAppCategory.MAIL,
            autoMapping = mapping,
        )
        assertEquals("summary", r.presetId)
        assertFalse(r.autoChosen)
    }

    @Test
    fun `auto mode uses the category mapping`() {
        val r = VoiceToneResolver.resolvePreset(
            sessionOverrideId = null,
            autoEnabled = true,
            manualId = "clean",
            category = VoiceAppCategory.MAIL,
            autoMapping = mapping,
        )
        assertEquals("keigo", r.presetId)
        assertTrue(r.autoChosen)
    }

    @Test
    fun `auto mode falls back to manual for unmapped category`() {
        val r = VoiceToneResolver.resolvePreset(
            sessionOverrideId = null,
            autoEnabled = true,
            manualId = "en",
            category = VoiceAppCategory.BROWSER,
            autoMapping = mapping,
        )
        assertEquals("en", r.presetId)
        assertFalse(r.autoChosen)
    }

    @Test
    fun `manual mode always uses the manual preset`() {
        val r = VoiceToneResolver.resolvePreset(
            sessionOverrideId = null,
            autoEnabled = false,
            manualId = "summary",
            category = VoiceAppCategory.MAIL,
            autoMapping = mapping,
        )
        assertEquals("summary", r.presetId)
        assertFalse(r.autoChosen)
    }
}
