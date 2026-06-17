package space.manus.nacre.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast

/**
 * Receives PackageInstaller session callbacks. The interesting case is
 * STATUS_PENDING_USER_ACTION: the OS hands back a confirmation intent that we
 * must launch so the user can approve the update.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) {
                    context.startActivity(confirm)
                } else {
                    Log.w(TAG, "PENDING_USER_ACTION without confirm intent")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(TAG, "Update installed")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Update install failed status=$status msg=$msg")
                Toast.makeText(context.applicationContext, "更新に失敗しました ($status)", Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val TAG = "NacreUpdate"
    }
}
