package com.sadad.app.feature.updates

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.IOException

/**
 * Hands the verified APK to the system installer as a session, not as a file URI.
 *
 * A session is what makes the result observable: the receiver hears «the user confirmed», «done»,
 * «cancelled» and «failed, with the system's own message». Seal's `ACTION_VIEW` intent and Mihon's
 * `ACTION_VIEW` + FileProvider both end at the installer sheet and learn nothing, and neither needs
 * the file to be streamed, so the APK stays in the app's cache.
 */
internal object UpdateInstaller {
  /** «تثبيت التطبيقات من هذا المصدر» for Sadad; off by default since Android 8. */
  fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

  fun allowInstallsIntent(context: Context): Intent =
      Intent(
          Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
          Uri.parse("package:${context.packageName}"),
      )

  /** True when a session was created and committed; the receiver reports what happened after. */
  fun start(context: Context, apk: File): Boolean =
      try {
        val installer = context.packageManager.packageInstaller
        val params =
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
              // The same package: the update replaces this copy, it does not add a second app.
              setAppPackageName(context.packageName)
              setSize(apk.length())
            }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
          session.openWrite(WRITE_NAME, 0, apk.length()).use { output ->
            apk.inputStream().use { it.copyTo(output) }
            session.fsync(output)
          }
          session.commit(callback(context, sessionId))
        }
        true
      } catch (_: IOException) {
        false
      } catch (_: SecurityException) {
        false
      } catch (_: IllegalArgumentException) {
        false
      }

  /**
   * Addressed to the app's own receiver. The installer writes the confirmation intent into it, so
   * it must be mutable from API 31 on; below that the flag does not exist.
   */
  private fun callback(context: Context, sessionId: Int): android.content.IntentSender {
    val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
    val mutable =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    return PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutable,
        )
        .intentSender
  }

  private const val WRITE_NAME = "base.apk"
}
