package space.manus.nacre.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a release APK and installs it as a self-update via PackageInstaller.
 * The new APK must be signed with the same key as the running app (it is — CI
 * uses the canonical NACRE_* release key), so the install is a seamless update
 * with no uninstall and no data loss. The user still taps a single system
 * confirmation, surfaced by [UpdateInstallReceiver].
 */
object ApkInstaller {
    private const val TAG = "NacreUpdate"
    const val ACTION_INSTALL_STATUS = "space.manus.nacre.update.INSTALL_STATUS"

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

    /** Commits [apk] into a PackageInstaller session. Runs on a worker thread. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("nacre.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
            // FLAG_MUTABLE is REQUIRED: PackageInstaller delivers the result by
            // filling EXTRA_STATUS into this intent via the IntentSender fillIn.
            // FLAG_IMMUTABLE would drop those extras and the receiver would never
            // see STATUS_PENDING_USER_ACTION. Do not "fix" this to immutable.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
        Log.i(TAG, "Install session $sessionId committed (${apk.length()} bytes)")
    }
}
