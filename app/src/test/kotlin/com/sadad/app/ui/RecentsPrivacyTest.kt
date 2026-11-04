package com.sadad.app.ui

import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.activity.ComponentActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A bare application: the manifest's SadadApplication arming StrictMode dies on the system's own
// closeable leaks while the picture-only activity is brought up.
@Config(application = android.app.Application::class)
class RecentsPrivacyTest {
  private fun secure(activity: ComponentActivity): Boolean =
      activity.window.attributes.flags and FLAG_SECURE != 0

  @Test
  @Config(sdk = [32])
  fun belowApi33TheWindowIsSecureOnlyWhilePaused() {
    val controller = Robolectric.buildActivity(ComponentActivity::class.java)
    controller.setup()
    val activity = controller.get()
    RecentsPrivacy.attach(activity)

    assertFalse("the app in front must not block screenshots", secure(activity))
    controller.pause()
    assertTrue(
        "the paused window must be secure, so recents cannot picture the ledger",
        secure(activity),
    )
    controller.resume()
    assertFalse("the app in front must not block screenshots", secure(activity))
  }

  @Test
  @Config(sdk = [33])
  fun atApi33TheFlagIsNeverSet() {
    val controller = Robolectric.buildActivity(ComponentActivity::class.java)
    controller.setup()
    val activity = controller.get()
    RecentsPrivacy.attach(activity)

    controller.pause()
    assertFalse(secure(activity))
  }

  @Test
  @Config(sdk = [36])
  fun atApi36TheFlagIsNeverSet() {
    val controller = Robolectric.buildActivity(ComponentActivity::class.java)
    controller.setup()
    val activity = controller.get()
    RecentsPrivacy.attach(activity)

    controller.pause()
    assertFalse(secure(activity))
  }
}
