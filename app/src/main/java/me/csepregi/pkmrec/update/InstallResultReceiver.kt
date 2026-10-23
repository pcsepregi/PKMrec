package me.csepregi.pkmrec.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Receives the PackageInstaller session result. If Android needs the user to confirm
 * the installation, the system confirmation screen is launched from here.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                    UpdateManager.onWaitingForUser()
                } else {
                    UpdateManager.onInstallFailed("nem sikerült megnyitni a telepítő ablakot")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // The app process is replaced by the new version; nothing to do.
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateManager.onInstallFailed("a telepítés megszakadt")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                UpdateManager.onInstallFailed(message ?: "ismeretlen hiba ($status)")
            }
        }
    }
}
