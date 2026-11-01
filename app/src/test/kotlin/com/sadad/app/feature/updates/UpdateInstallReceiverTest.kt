package com.sadad.app.feature.updates

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The installer hands its confirmation sheet back as a parcel, and the platform marks every intent
 * it unparcels that way. Launching that object is an "unsafe intent launch" — in a debug build the
 * StrictMode policy turns it into a crash, which is how the first real update ended: the download
 * finished, the session was created, and the app died opening the sheet. The receiver therefore
 * rebuilds the sheet instead of forwarding it, and these tests hold that rebuild to the parts the
 * installer actually needs, and to the check that keeps the launch aimed at the platform's own
 * confirm sheet.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateInstallReceiverTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val session = 41

  /** The intent the installer writes into `EXTRA_INTENT`, after a trip through a parcel. */
  private fun fromInstaller(): Intent {
    val original =
        Intent(ACTION_CONFIRM_INSTALL).apply {
          setPackage(INSTALLER)
          putExtra(EXTRA_SESSION, session)
        }
    val parcel = Parcel.obtain()
    return try {
      parcel.writeParcelable(original, 0)
      parcel.setDataPosition(0)
      parcel.readParcelable<Intent>(Intent::class.java.classLoader)!!
    } finally {
      parcel.recycle()
    }
  }

  /** An activity of [pkg] that answers the confirm action, as ShadowPackageManager needs it. */
  private fun registerSheet(pkg: String, flags: Int) =
      shadowOf(context.packageManager)
          .addResolveInfoForIntent(
              Intent(ACTION_CONFIRM_INSTALL).setPackage(pkg),
              ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                      name = "$pkg.Sheet"
                      packageName = pkg
                      applicationInfo =
                          ApplicationInfo().apply {
                            packageName = pkg
                            this.flags = flags
                          }
                    }
                resolvePackageName = pkg
              },
          )

  @Test
  fun theSheetIsRebuiltRatherThanForwarded() {
    registerSheet(INSTALLER, ApplicationInfo.FLAG_SYSTEM)
    val confirm = fromInstaller()

    val launch = UpdateInstallReceiver().launchable(context, confirm)!!

    assertNotSame(confirm, launch)
    assertEquals(ACTION_CONFIRM_INSTALL, launch.action)
    assertEquals(INSTALLER, launch.`package`)
  }

  @Test
  fun theSheetKeepsTheSessionTheInstallerNeeds() {
    registerSheet(INSTALLER, ApplicationInfo.FLAG_SYSTEM)
    val launch = UpdateInstallReceiver().launchable(context, fromInstaller())!!

    assertEquals(session, launch.getIntExtra(EXTRA_SESSION, -1))
  }

  @Test
  fun theSheetOpensFromABroadcastReceiver() {
    registerSheet(INSTALLER, ApplicationInfo.FLAG_SYSTEM)
    val launch = UpdateInstallReceiver().launchable(context, fromInstaller())!!

    assertTrue(launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
  }

  @Test
  fun anIntentWithoutASheetIsNotLaunched() {
    val launch = UpdateInstallReceiver().launchable(context, Intent(ACTION_CONFIRM_INSTALL))!!

    assertFalse(launch.hasExtra(EXTRA_SESSION))
    assertEquals(ACTION_CONFIRM_INSTALL, launch.action)
  }

  @Test
  fun anIntentThatIsNotThePlatformConfirmActionIsRefused() {
    val confirm =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse("content://x/pkg"), null)
            .setPackage(INSTALLER)
            .putExtra(EXTRA_SESSION, session)

    assertNull(UpdateInstallReceiver().launchable(context, confirm))
  }

  @Test
  fun theConfirmActionOfANonSystemAppIsRefused() {
    registerSheet(NON_SYSTEM, flags = 0)
    val confirm = Intent(ACTION_CONFIRM_INSTALL).setPackage(NON_SYSTEM)

    assertNull(UpdateInstallReceiver().launchable(context, confirm))
  }

  @Test
  fun theConfirmActionOfASystemAppLaunchesWithItsSessionExtra() {
    registerSheet(VENDOR_INSTALLER, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)
    val confirm =
        Intent(ACTION_CONFIRM_INSTALL).setPackage(VENDOR_INSTALLER).putExtra(EXTRA_SESSION, session)

    val launch = UpdateInstallReceiver().launchable(context, confirm)!!

    assertEquals(session, launch.getIntExtra(EXTRA_SESSION, -1))
    assertEquals(VENDOR_INSTALLER, launch.`package`)
  }

  private companion object {
    const val ACTION_CONFIRM_INSTALL = "android.content.pm.action.CONFIRM_INSTALL"
    const val INSTALLER = "com.google.android.packageinstaller"
    const val NON_SYSTEM = "com.example.notasystemapp"
    const val VENDOR_INSTALLER = "com.miui.packageinstaller"
    const val EXTRA_SESSION = "android.content.pm.extra.SESSION_ID"
  }
}
