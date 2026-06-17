package space.manus.nacre.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a release APK and hands it to the system installer as a seamless
 * self-update. The new APK is signed with the same key as the running app (CI
 * uses the canonical NACRE_* release key), so it updates in place — no
 * uninstall, no data loss; the user taps one system confirmation.
 *
 * The install is launched with ACTION_VIEW + a FileProvider URI from the
 * foreground activity. The earlier PackageInstaller-session approach relied on a
 * BroadcastReceiver to surface the confirm dialog, which Android 16 / Samsung
 * Freecess blocks (background-activity-launch restriction) — so it never
 * appeared. ACTION_VIEW from a visible activity is the reliable path.
 */
object ApkInstaller {
    private const val TAG = "NacreUpdate"
    private const val PROVIDER_SUFFIX = ".updateprovider"

    /** True when the OS already allows this app to install packages. */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Settings screen where the user grants "install unknown apps" for Nacre. */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Streams [url] to the app cache. [onProgress] gets 0f..1f when the size is
     * known. Runs on the caller's worker thread. Returns the downloaded file.
     */
    fun download(context: Context, url: String, expectedSize: Long, onProgress: (Float) -> Unit): File {
        val out = File(context.cacheDir, "nacre-update.apk")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Nacre-Updater")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            val size = if (expectedSize > 0) expectedSize else conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        total += read
                        if (size > 0) onProgress((total.toFloat() / size).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return out
    }

    /**
     * Opens the system installer for [apk]. Call from the foreground activity
     * (main thread) so the confirm dialog is allowed to appear.
     */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + PROVIDER_SUFFIX, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.i(TAG, "Launched system installer for ${apk.name} (${apk.length()} bytes)")
    }
}
