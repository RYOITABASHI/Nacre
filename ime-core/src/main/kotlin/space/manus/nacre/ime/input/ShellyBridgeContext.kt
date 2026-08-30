package space.manus.nacre.ime.input

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Shelly Bridge — reads ephemeral terminal context shared by the Shelly Android
 * terminal IDE (`dev.shelly.terminal`) so Nacre's conversion/prediction pipeline
 * can surface repo names, git branches, cwd path segments, and safe technical
 * terms while Shelly is the foreground app.
 *
 * ## Transfer mechanism
 * Shelly and Nacre are signed with different keys, so a ContentProvider guarded
 * by a signature permission is not an option. Instead Shelly writes a JSON
 * snapshot to a fixed, world-readable path under shared external storage:
 *
 *   /storage/emulated/0/Android/media/dev.shelly.terminal/nacre-bridge/context.json
 *
 * Nacre polls this file's `lastModified()` timestamp cheaply and only re-parses
 * when it changes (see [ShellyBridgeReader.current]).
 *
 * ## Contract (fixed — do not change without updating the Shelly side too)
 * ```json
 * {
 *   "schema": 1,
 *   "generatedAt": 1735500000000,
 *   "expiresAt": 1735500300000,
 *   "repo": "Shelly",
 *   "branch": "main",
 *   "cwdSegments": ["Users", "info", "Shelly", "components"],
 *   "terms": ["pnpm", "jest", "terminal-store", "ConfigTUI"]
 * }
 * ```
 * `repo`/`branch` may be absent. Any entry with `now > expiresAt` is stale and
 * must be discarded entirely.
 *
 * ## Defense in depth
 * Shelly is expected to sanitize before writing, but Nacre must never trust the
 * writer: an IME has access to every keystroke the user types, so a corrupted or
 * malicious bridge file must not be able to inject secrets or garbage into the
 * candidate list. Every string field is re-validated independently by
 * [sanitizeToken] below — this is the single source of truth for what is safe to
 * surface, and it is exercised directly by unit tests (see
 * `ShellyBridgeContextTest`).
 */
data class ShellyBridgeContext(
    val schema: Int,
    val generatedAt: Long,
    val expiresAt: Long,
    val repo: String?,
    val branch: String?,
    val cwdSegments: List<String>,
    val terms: List<String>,
) {
    /** All sanitized terms this context can offer as prediction candidates, deduplicated. */
    fun allTerms(): List<String> {
        val pool = LinkedHashSet<String>()
        repo?.let { pool.add(it) }
        branch?.let { pool.add(it) }
        pool.addAll(cwdSegments)
        pool.addAll(terms)
        return pool.toList()
    }
}

/** Schema version this reader understands. The contract is fixed; bump only in lockstep with Shelly. */
private const val SHELLY_BRIDGE_SCHEMA_VERSION = 1

private const val MAX_CWD_SEGMENTS = 10
private const val MAX_TERMS = 20

/** Character-class allowlist for every individual token (repo/branch/cwd segment/term). */
private val TOKEN_CHAR_PATTERN = Regex("^[A-Za-z0-9_.\\-/]{1,40}$")

/** Known secret/API-key prefixes. Any token starting with one of these is discarded outright. */
private val SECRET_PREFIXES = listOf(
    "sk-", "ghp_", "github_pat_", "glpat-", "xoxb-", "xoxp-",
    "AKIA", "ASIA", "AIza", "ya29.", "eyJ",
)

/** High-entropy-looking base64/hex blobs (>= 20 chars) are discarded even if otherwise well-formed. */
private val BASE64_ENTROPY_PATTERN = Regex("^[A-Za-z0-9+/=]{20,}$")
private val HEX_ENTROPY_PATTERN = Regex("^[0-9a-fA-F]{20,}$")

/**
 * Re-validate a single token from the bridge file. Returns the token unchanged if it passes
 * every check, or null if it must be discarded.
 *
 * This is intentionally a pure function (no I/O, no Android dependency) so it can be unit
 * tested exhaustively without Robolectric/instrumentation.
 */
fun sanitizeToken(raw: String?): String? {
    if (raw.isNullOrEmpty()) return null
    if (!TOKEN_CHAR_PATTERN.matches(raw)) return null
    for (prefix in SECRET_PREFIXES) {
        if (raw.startsWith(prefix)) return null
    }
    if (BASE64_ENTROPY_PATTERN.matches(raw)) return null
    if (HEX_ENTROPY_PATTERN.matches(raw)) return null
    return raw
}

/** Sanitizes a list of raw tokens, dropping invalid ones and truncating to [maxCount]. */
fun sanitizeTokenList(raw: List<String?>, maxCount: Int): List<String> {
    val result = ArrayList<String>(minOf(raw.size, maxCount))
    for (item in raw) {
        val clean = sanitizeToken(item) ?: continue
        result.add(clean)
        if (result.size >= maxCount) break
    }
    return result
}

/**
 * Parses and re-sanitizes the raw JSON text of the bridge file. Returns null (never throws) if
 * the text is malformed, the schema is unrecognized, or the JSON shape doesn't match the
 * contract — the caller is expected to treat null the same as "no bridge context available".
 */
fun parseShellyBridgeContext(jsonText: String): ShellyBridgeContext? {
    return try {
        val obj = JSONObject(jsonText)
        val schema = obj.optInt("schema", -1)
        if (schema != SHELLY_BRIDGE_SCHEMA_VERSION) return null

        val generatedAt = obj.optLong("generatedAt", 0L)
        val expiresAt = obj.optLong("expiresAt", 0L)

        val repoRaw = obj.optString("repo")
        val repo = if (repoRaw.isNotEmpty()) sanitizeToken(repoRaw) else null

        val branchRaw = obj.optString("branch")
        val branch = if (branchRaw.isNotEmpty()) sanitizeToken(branchRaw) else null

        val cwdSegmentsRaw = ArrayList<String?>()
        obj.optJSONArray("cwdSegments")?.let { arr ->
            for (i in 0 until arr.length()) cwdSegmentsRaw.add(arr.optString(i, null))
        }
        val cwdSegments = sanitizeTokenList(cwdSegmentsRaw, MAX_CWD_SEGMENTS)

        val termsRaw = ArrayList<String?>()
        obj.optJSONArray("terms")?.let { arr ->
            for (i in 0 until arr.length()) termsRaw.add(arr.optString(i, null))
        }
        val terms = sanitizeTokenList(termsRaw, MAX_TERMS)

        ShellyBridgeContext(
            schema = schema,
            generatedAt = generatedAt,
            expiresAt = expiresAt,
            repo = repo,
            branch = branch,
            cwdSegments = cwdSegments,
            terms = terms,
        )
    } catch (e: JSONException) {
        null
    } catch (e: Exception) {
        // Defensive catch-all: a malformed bridge file must never crash the IME.
        null
    }
}

/** True when [nowMs] has not yet passed [ShellyBridgeContext.expiresAt]. */
fun isShellyBridgeContextFresh(ctx: ShellyBridgeContext, nowMs: Long): Boolean {
    return nowMs <= ctx.expiresAt
}

/**
 * Reads, caches, and re-sanitizes the Shelly bridge file.
 *
 * Cheap to poll on every keystroke: [current] only touches disk (`File.lastModified()`) and
 * only re-parses the JSON body when the mtime actually changes.
 */
class ShellyBridgeReader(context: Context) {

    private val appContext = context.applicationContext ?: context

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Master on/off switch for the whole feature. Defaults to enabled. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    private val bridgeFile: File by lazy {
        File(
            Environment.getExternalStorageDirectory(),
            "Android/media/dev.shelly.terminal/nacre-bridge/context.json",
        )
    }

    @Volatile private var cachedLastModified: Long = -1L
    @Volatile private var cachedContext: ShellyBridgeContext? = null

    /**
     * Returns the current sanitized bridge context, or null if the feature is disabled, the
     * file is missing/unreadable, the JSON is invalid, or the context has expired.
     */
    fun current(nowMs: Long = System.currentTimeMillis()): ShellyBridgeContext? {
        if (!enabled) return null

        val lastModified = try {
            bridgeFile.lastModified()
        } catch (e: Exception) {
            0L
        }

        if (lastModified == 0L) {
            // File missing or inaccessible.
            cachedLastModified = 0L
            cachedContext = null
            return null
        }

        if (lastModified != cachedLastModified) {
            cachedLastModified = lastModified
            cachedContext = readAndParse()
        }

        val ctx = cachedContext ?: return null
        return if (isShellyBridgeContextFresh(ctx, nowMs)) ctx else null
    }

    private fun readAndParse(): ShellyBridgeContext? {
        return try {
            val text = bridgeFile.readText(Charsets.UTF_8)
            parseShellyBridgeContext(text)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read Shelly bridge context: ${e.message}")
            null
        }
    }

    /**
     * Sanitized bridge terms (repo/branch/cwd segments/terms) whose value starts with [prefix]
     * (case-insensitive). Returns an empty list when disabled, expired, or nothing matches —
     * callers should treat this purely as an additive, low-priority candidate source.
     */
    fun matchingTerms(prefix: String, nowMs: Long = System.currentTimeMillis()): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val ctx = current(nowMs) ?: return emptyList()
        val prefixLower = prefix.lowercase()
        return ctx.allTerms().filter { it.lowercase().startsWith(prefixLower) }
    }

    companion object {
        private const val TAG = "ShellyBridgeContext"
        private const val PREFS_NAME = "nacre_shelly_bridge"
        private const val KEY_ENABLED = "enabled"
    }
}
