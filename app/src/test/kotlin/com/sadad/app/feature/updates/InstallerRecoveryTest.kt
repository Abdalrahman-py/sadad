package com.sadad.app.feature.updates

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Regression coverage for blocked or damaged handoffs; physical OEM validation is separate. */
@RunWith(RobolectricTestRunner::class)
class InstallerRecoveryTest {
  @Test
  fun aBlockedLaunchCanBeRetriedFromTheVisibleScreenUsingTheSameSession() {
    val app = ApplicationProvider.getApplicationContext<Context>()
    val center = UpdateCenter.get(app)
    val confirm =
        Intent("android.content.pm.action.CONFIRM_INSTALL")
            .putExtra("android.content.pm.extra.SESSION_ID", 41)
    val blocked =
        object : ContextWrapper(app) {
          override fun startActivity(intent: Intent) {
            throw SecurityException("launch denied")
          }
        }
    center.confirmInstallation(blocked, confirm)
    assertEquals(UpdateState.Phase.READY, center.state.value.phase)
    var openedSession = -1
    val foreground =
        object : ContextWrapper(app) {
          override fun startActivity(intent: Intent) {
            openedSession = intent.getIntExtra("android.content.pm.extra.SESSION_ID", -1)
          }
        }
    center.retryInstallation(foreground)
    assertEquals(41, openedSession)
    assertEquals(UpdateState.Phase.INSTALLING, center.state.value.phase)
    assertEquals(null, center.state.value.error)
    center.installFailed(UpdateError.INSTALL_CANCELLED)
  }

  @Test
  fun aMissingSystemConfirmationReportsFailure() {
    val app = ApplicationProvider.getApplicationContext<Context>()
    val center = UpdateCenter.get(app)
    center.installFailed(UpdateError.INSTALL_CANCELLED)
    UpdateInstallReceiver()
        .onReceive(
            app,
            Intent()
                .putExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_PENDING_USER_ACTION,
                ),
        )
    assertEquals(UpdateError.INSTALL_FAILED, center.state.value.error)
  }

  @Test
  fun rebuildingTheSystemConfirmationMustKeepItsDataUri() {
    val app = ApplicationProvider.getApplicationContext<Context>()
    val uri = Uri.parse("content://installer/session/41")
    val confirm =
        Intent("android.content.pm.action.CONFIRM_INSTALL")
            .setDataAndType(uri, "application/vnd.android.package-archive")
    val launch = UpdateInstallReceiver().launchable(app, confirm)!!
    assertEquals("Rebuilding the confirmation must not discard its data", uri, launch.data)
  }

  @Test
  fun failingToOpenTheInstallerMustReportAnErrorInsteadOfWaitingForever() {
    val app = ApplicationProvider.getApplicationContext<Context>()
    val context =
        object : ContextWrapper(app) {
          override fun startActivity(intent: Intent) {
            throw ActivityNotFoundException("simulated unavailable installer")
          }
        }
    val center = UpdateCenter.get(app)
    center.installFailed(UpdateError.INSTALL_CANCELLED)
    val callback =
        Intent()
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
            .putExtra(Intent.EXTRA_INTENT, Intent("android.content.pm.action.CONFIRM_INSTALL"))
    UpdateInstallReceiver().onReceive(context, callback)
    assertEquals(
        "A failed system launch must reach the screen",
        UpdateError.INSTALL_FAILED,
        center.state.value.error,
    )
  }
}
