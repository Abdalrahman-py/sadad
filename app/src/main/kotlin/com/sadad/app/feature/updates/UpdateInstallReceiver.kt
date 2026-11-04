package com.sadad.app.feature.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
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
      // The system needs one tap in its own sheet; carry on into it — the only intent we will
      // open, resolved against a system app.
      PackageInstaller.STATUS_PENDING_USER_ACTION -> {
        val launch = confirmation(intent)?.let { launchable(context, it) }
        if (launch == null) center.installFailed(UpdateError.INSTALL_FAILED)
        else center.confirmInstallation(context, launch)
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
   * The sheet the installer wants us to open, rebuilt by this app, after checking it is the
   * platform's confirm-install sheet of a system app; anything else is asked no more.
   *
   * Launching the intent the installer handed us is an "unsafe intent launch": the platform marks
   * every intent it unparcels from another process, and the debug StrictMode policy kills the app
   * on that violation — which is exactly what happened the first time an update reached the
   * installer. Copying does not help on its own: `Intent(other)` inherits the marker,
   * `putExtras(other)` sets it again, and copying a bundle that has not been unparcelled yet sets
   * it too. So the fields are copied one by one into an intent this app built, and the extras are
   * pulled out of their parcelled form first.
   */
  internal fun launchable(context: Context, confirm: Intent): Intent? {
    // Only the platform's confirm action may come back out of this app, whatever else the
    // installer's broadcast carries.
    if (confirm.action != ACTION_CONFIRM_INSTALL) return null
    val launch = copyOf(confirm)
    // A sheet with a component or package must resolve to a system app: MIUI and other vendors
    // ship their own installers, so nothing is allowed by name.
    if (launch.component != null || launch.`package` != null) {
      val resolved = context.packageManager.resolveActivity(launch, 0) ?: return null
      val flags = resolved.activityInfo.applicationInfo.flags
      if (
          flags and ApplicationInfo.FLAG_SYSTEM == 0 &&
              flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
      )
          return null
    }
    return launch
  }

  private fun copyOf(confirm: Intent): Intent =
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

  companion object {
    private const val ACTION_CONFIRM_INSTALL = "android.content.pm.action.CONFIRM_INSTALL"
  }
}
