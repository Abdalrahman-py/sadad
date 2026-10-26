package com.sadad.app.feature.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * The installer's answer. Everything here runs on the main thread, so each branch only records what
 * happened; the state stores do their own disk work off it.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    val center = UpdateCenter.get(context.applicationContext)
    when (status) {
      // The system needs one tap in its own sheet; carry on into it.
      PackageInstaller.STATUS_PENDING_USER_ACTION -> {
        val confirm = confirmation(intent) ?: return
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(confirm) }
      }
      PackageInstaller.STATUS_SUCCESS -> center.installed()
      PackageInstaller.STATUS_FAILURE_ABORTED -> center.installFailed(UpdateError.INSTALL_CANCELLED)
      else -> center.installFailed(UpdateError.INSTALL_FAILED)
    }
  }

  private fun confirmation(intent: Intent): Intent? =
      if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
      } else {
        @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
      }
}
