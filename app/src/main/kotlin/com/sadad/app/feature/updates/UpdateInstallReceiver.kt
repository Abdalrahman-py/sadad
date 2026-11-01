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
        val confirm = confirmation(intent)
        if (confirm == null) center.installFailed(UpdateError.INSTALL_FAILED)
        else center.confirmInstallation(context, launchable(confirm))
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

  /**
   * The sheet the installer wants us to open, rebuilt by this app.
   *
   * Launching the intent the installer handed us is an "unsafe intent launch": the platform marks
   * every intent it unparcels from another process, and the debug StrictMode policy kills the app
   * on that violation — which is exactly what happened the first time an update reached the
   * installer. Copying does not help on its own: `Intent(other)` inherits the marker,
   * `putExtras(other)` sets it again, and copying a bundle that has not been unparcelled yet sets
   * it too. So the fields are copied one by one into an intent this app built, and the extras are
   * pulled out of their parcelled form first.
   */
  internal fun launchable(confirm: Intent): Intent =
      Intent(confirm.action).apply {
        setPackage(confirm.`package`)
        component = confirm.component
        setDataAndType(confirm.data, confirm.type)
        confirm.categories?.forEach { addCategory(it) }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        confirm.extras?.let { source ->
          source.keySet()
          putExtras(source)
        }
      }
}
