package space.manus.nacre.ime.input

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the Shelly↔Nacre bridge's re-sanitization layer.
 *
 * Shelly is expected to sanitize its context.json before writing it, but Nacre must never
 * trust the writer — an IME sees every keystroke, so a corrupted or malicious bridge file
 * must not be able to smuggle secrets or garbage into the candidate list. These tests pin
 * down exactly what [sanitizeToken] / [parseShellyBridgeContext] accept and reject.
 */
class ShellyBridgeContextTest {

    // --- sanitizeToken: character-class allowlist ---

    @Test
    fun `accepts a plain alphanumeric token`() {
        assertEquals("pnpm", sanitizeToken("pnpm"))
    }

    @Test
    fun `accepts tokens with underscore dot dash and slash`() {
        assertEquals("terminal-store", sanitizeToken("terminal-store"))
        assertEquals("ConfigTUI", sanitizeToken("ConfigTUI"))
        assertEquals("dev.shelly.terminal", sanitizeToken("dev.shelly.terminal"))
        assertEquals("Users/info/Shelly", sanitizeToken("Users/info/Shelly"))
        assertEquals("feature_branch", sanitizeToken("feature_branch"))
    }

    @Test
    fun `rejects null or empty input`() {
        assertNull(sanitizeToken(null))
        assertNull(sanitizeToken(""))
    }

    @Test
    fun `rejects tokens with disallowed characters`() {
        assertNull(sanitizeToken("hello world")) // space
        assertNull(sanitizeToken("rm -rf \$HOME")) // shell metacharacters
        assertNull(sanitizeToken("a\nb")) // newline
        assertNull(sanitizeToken("日本語")) // non-ASCII
        assertNull(sanitizeToken("<script>")) // markup
        assertNull(sanitizeToken("a;b"))
    }

    @Test
    fun `rejects tokens longer than 40 characters`() {
        // A trailing dash keeps this out of both entropy patterns (neither the base64 nor the
        // hex charset includes '-'), isolating the length check from the entropy checks below.
        val tooLong = "z".repeat(40) + "-"
        assertEquals(41, tooLong.length)
        assertNull(sanitizeToken(tooLong))
        val exactly40 = "z".repeat(39) + "-"
        assertEquals(40, exactly40.length)
        assertEquals(exactly40, sanitizeToken(exactly40))
    }

    // --- sanitizeToken: known secret prefixes ---

    @Test
    fun `rejects known secret key prefixes`() {
        val secrets = listOf(
            "sk-abcdefghijklmnop",
            "ghp_abcdefghijklmnopqrstuvwxyz",
            "github_pat_abcdefghijklmno",
            "glpat-abcdefghijklmnop",
            "xoxb-1234567890-abcdefg",
            "xoxp-1234567890-abcdefg",
            "AKIAABCDEFGHIJKLMNOP",
            "ASIAABCDEFGHIJKLMNOP",
            "AIzaSyAbCdEfGhIjKlMnOp",
            "ya29.abcdefghijklmnop",
            "eyJhbGciOiJIUzI1NiJ9",
        )
        for (secret in secrets) {
            assertNull("expected '$secret' to be rejected", sanitizeToken(secret))
        }
    }

    // --- sanitizeToken: high-entropy blobs ---

    @Test
    fun `rejects long base64-looking strings`() {
        assertNull(sanitizeToken("QUJDREVGR0hJSktMTU5PUFFSU1RVVg=="))
    }

    @Test
    fun `rejects long hex-looking strings`() {
        assertNull(sanitizeToken("0123456789abcdef0123456789abcdef"))
    }

    @Test
    fun `allows short hex-like tokens under the entropy threshold`() {
        // 19 chars — one under the >=20 entropy cutoff — must still pass.
        val shortHex = "0123456789abcdef012"
        assertEquals(19, shortHex.length)
        assertEquals(shortHex, sanitizeToken(shortHex))
    }

    @Test
    fun `allows ordinary identifiers that happen to be long but not entropy-shaped`() {
        // Mixed-case with a dash breaks both the base64 charset (case is fine, but this
        // also isn't purely hex) — this is exactly the kind of legit term (a real repo dir
        // name) that must not be treated as a secret.
        assertEquals("terminal-emulator-native-module", sanitizeToken("terminal-emulator-native-module"))
    }

    // --- sanitizeTokenList ---

    @Test
    fun `sanitizeTokenList drops invalid entries and keeps valid ones`() {
        val raw = listOf("pnpm", "sk-secretvalue", "jest", null, "bad token", "ConfigTUI")
        assertEquals(listOf("pnpm", "jest", "ConfigTUI"), sanitizeTokenList(raw, maxCount = 20))
    }

    @Test
    fun `sanitizeTokenList truncates to maxCount`() {
        val raw = (1..30).map { "term$it" }
        val result = sanitizeTokenList(raw, maxCount = 20)
        assertEquals(20, result.size)
        assertEquals("term1", result.first())
        assertEquals("term20", result.last())
    }

    // --- parseShellyBridgeContext ---

    @Test
    fun `parses a well-formed context matching the fixed schema`() {
        val json = """
            {
              "schema": 1,
              "generatedAt": 1735500000000,
              "expiresAt": 1735500300000,
              "repo": "Shelly",
              "branch": "main",
              "cwdSegments": ["Users", "info", "Shelly", "components"],
              "terms": ["pnpm", "jest", "terminal-store", "ConfigTUI"]
            }
        """.trimIndent()

        val ctx = parseShellyBridgeContext(json)
        assertNotNull(ctx)
        ctx!!
        assertEquals(1, ctx.schema)
        assertEquals(1735500000000L, ctx.generatedAt)
        assertEquals(1735500300000L, ctx.expiresAt)
        assertEquals("Shelly", ctx.repo)
        assertEquals("main", ctx.branch)
        assertEquals(listOf("Users", "info", "Shelly", "components"), ctx.cwdSegments)
        assertEquals(listOf("pnpm", "jest", "terminal-store", "ConfigTUI"), ctx.terms)
    }

    @Test
    fun `parses a context with repo and branch omitted`() {
        val json = """
            {
              "schema": 1,
              "generatedAt": 1,
              "expiresAt": 2,
              "cwdSegments": ["Users"],
              "terms": []
            }
        """.trimIndent()

        val ctx = parseShellyBridgeContext(json)
        assertNotNull(ctx)
        assertNull(ctx!!.repo)
        assertNull(ctx.branch)
    }

    @Test
    fun `rejects an unrecognized schema version`() {
        val json = """{"schema": 2, "generatedAt": 1, "expiresAt": 2}"""
        assertNull(parseShellyBridgeContext(json))
    }

    @Test
    fun `rejects a context missing the schema field entirely`() {
        val json = """{"generatedAt": 1, "expiresAt": 2}"""
        assertNull(parseShellyBridgeContext(json))
    }

    @Test
    fun `returns null instead of throwing for malformed JSON`() {
        assertNull(parseShellyBridgeContext("{not valid json"))
        assertNull(parseShellyBridgeContext(""))
        assertNull(parseShellyBridgeContext("null"))
        assertNull(parseShellyBridgeContext("[1,2,3]"))
    }

    @Test
    fun `re-sanitizes repo branch cwdSegments and terms even inside otherwise valid JSON`() {
        // Simulates a compromised or buggy Shelly writer that skipped its own sanitization —
        // Nacre must still strip the secret-shaped values.
        val json = """
            {
              "schema": 1,
              "generatedAt": 1,
              "expiresAt": 99999999999999,
              "repo": "sk-leakedsecretvalue",
              "branch": "feature branch with spaces",
              "cwdSegments": ["Users", "ghp_leakedtoken1234567890", "Shelly"],
              "terms": ["pnpm", "AKIAABCDEFGHIJKLMNOP", "0123456789abcdef0123456789abcdef", "jest"]
            }
        """.trimIndent()

        val ctx = parseShellyBridgeContext(json)
        assertNotNull(ctx)
        ctx!!
        assertNull(ctx.repo) // secret-prefixed → dropped
        assertNull(ctx.branch) // contains spaces → dropped
        assertEquals(listOf("Users", "Shelly"), ctx.cwdSegments) // secret entry dropped
        assertEquals(listOf("pnpm", "jest"), ctx.terms) // secret + hex-entropy entries dropped
    }

    @Test
    fun `caps cwdSegments at 10 and terms at 20 even when the JSON provides more`() {
        val cwd = (1..15).joinToString(",") { "\"seg$it\"" }
        val terms = (1..25).joinToString(",") { "\"term$it\"" }
        val json = """
            {
              "schema": 1,
              "generatedAt": 1,
              "expiresAt": 2,
              "cwdSegments": [$cwd],
              "terms": [$terms]
            }
        """.trimIndent()

        val ctx = parseShellyBridgeContext(json)
        assertNotNull(ctx)
        assertEquals(10, ctx!!.cwdSegments.size)
        assertEquals(20, ctx.terms.size)
    }

    // --- isShellyBridgeContextFresh ---

    @Test
    fun `is fresh strictly before expiresAt`() {
        val ctx = ShellyBridgeContext(1, 0, 1000, null, null, emptyList(), emptyList())
        assertTrue(isShellyBridgeContextFresh(ctx, nowMs = 500))
    }

    @Test
    fun `is fresh exactly at expiresAt`() {
        val ctx = ShellyBridgeContext(1, 0, 1000, null, null, emptyList(), emptyList())
        assertTrue(isShellyBridgeContextFresh(ctx, nowMs = 1000))
    }

    @Test
    fun `is stale strictly after expiresAt`() {
        val ctx = ShellyBridgeContext(1, 0, 1000, null, null, emptyList(), emptyList())
        assertFalse(isShellyBridgeContextFresh(ctx, nowMs = 1001))
    }

    // --- ShellyBridgeContext#allTerms ---

    @Test
    fun `allTerms combines repo branch cwdSegments and terms deduplicated`() {
        val ctx = ShellyBridgeContext(
            schema = 1,
            generatedAt = 0,
            expiresAt = 0,
            repo = "Shelly",
            branch = "main",
            cwdSegments = listOf("Shelly", "components"), // "Shelly" duplicates repo
            terms = listOf("pnpm", "main"), // "main" duplicates branch
        )
        assertEquals(listOf("Shelly", "main", "components", "pnpm"), ctx.allTerms())
    }
}
