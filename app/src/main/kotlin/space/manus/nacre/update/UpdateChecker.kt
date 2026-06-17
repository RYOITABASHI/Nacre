package space.manus.nacre.update

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A newer release discovered on GitHub. */
data class UpdateInfo(
    val versionCode: Int,
    val tag: String,
    val apkUrl: String,
    val apkSize: Long,
    val releaseNotes: String,
    val releaseUrl: String,
)

/**
 * Checks the public GitHub Releases of RYOITABASHI/Nacre for a build whose
 * versionCode is higher than the running app. The signed APK is published by CI
 * as an asset named `nacre-<versionCode>.apk`; the versionCode is parsed from
 * that name. Unauthenticated GitHub API (60 req/h/IP) — fine for manual checks.
 */
object UpdateChecker {
    private const val TAG = "NacreUpdate"
    private const val RELEASES_API =
        "https://api.github.com/repos/RYOITABASHI/Nacre/releases/latest"
    private val ASSET_RE = Regex("""^nacre-(\d+)\.apk$""")

    /** Returns the newer build, or null when already up to date. Throws on network/parse errors. */
    fun check(currentVersionCode: Int): UpdateInfo? {
        val conn = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Nacre-Updater")
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        try {
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "releases/latest HTTP $status")
                return null
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val notes = json.optString("body", "")
            val htmlUrl = json.optString("html_url", "")
            val tag = json.optString("tag_name", "")
            val assets = json.optJSONArray("assets") ?: return null

            var best: UpdateInfo? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val match = ASSET_RE.find(a.optString("name")) ?: continue
                val vc = match.groupValues[1].toIntOrNull() ?: continue
                val url = a.optString("browser_download_url")
                if (url.isBlank()) continue
                if (best == null || vc > best.versionCode) {
                    best = UpdateInfo(vc, tag.ifBlank { "build-$vc" }, url, a.optLong("size", 0L), notes, htmlUrl)
                }
            }
            val candidate = best ?: return null
            return if (candidate.versionCode > currentVersionCode) candidate else null
        } finally {
            conn.disconnect()
        }
    }
}
