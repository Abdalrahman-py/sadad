package com.sadad.app.feature.updates

import android.content.Intent
import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The installer hands its confirmation sheet back as a parcel, and the platform marks every intent
 * it unparcels that way. Launching that object is an "unsafe intent launch" — in a debug build the
 * StrictMode policy turns it into a crash, which is how the first real update ended: the download
 * finished, the session was created, and the app died opening the sheet. The receiver therefore
 * rebuilds the sheet instead of forwarding it, and these tests hold that rebuild to the parts the
 * installer actually needs.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateInstallReceiverTest {
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

  @Test
  fun theSheetIsRebuiltRatherThanForwarded() {
    val confirm = fromInstaller()

    val launch = UpdateInstallReceiver().launchable(confirm)

    assertNotSame(confirm, launch)
    assertEquals(ACTION_CONFIRM_INSTALL, launch.action)
    assertEquals(INSTALLER, launch.`package`)
  }

  @Test
  fun theSheetKeepsTheSessionTheInstallerNeeds() {
    val launch = UpdateInstallReceiver().launchable(fromInstaller())

    assertEquals(session, launch.getIntExtra(EXTRA_SESSION, -1))
  }

  @Test
  fun theSheetOpensFromABroadcastReceiver() {
    val launch = UpdateInstallReceiver().launchable(fromInstaller())

    assertTrue(launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
  }

  @Test
  fun anIntentWithoutASheetIsNotLaunched() {
    val launch = UpdateInstallReceiver().launchable(Intent(ACTION_CONFIRM_INSTALL))

    assertFalse(launch.hasExtra(EXTRA_SESSION))
    assertEquals(ACTION_CONFIRM_INSTALL, launch.action)
  }

  private companion object {
    const val ACTION_CONFIRM_INSTALL = "android.content.pm.action.CONFIRM_INSTALL"
    const val INSTALLER = "com.google.android.packageinstaller"
    const val EXTRA_SESSION = "android.content.pm.extra.SESSION_ID"
  }
}
