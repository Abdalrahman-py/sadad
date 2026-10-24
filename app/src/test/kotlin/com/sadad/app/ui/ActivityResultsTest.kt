package com.sadad.app.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** MainActivity's FragmentActivity host must accept the registry's full-width request codes. */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class ActivityResultsTest {
  @Test
  fun theActivityResultRegistryCanLaunchSpeechRecognition() {
    Robolectric.buildActivity(FragmentActivity::class.java).setup().use { controller ->
      val activity = controller.get()
      val launcher =
          activity.activityResultRegistry.register(
              "voice-draft",
              ActivityResultContracts.StartActivityForResult(),
          ) {}

      launcher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH))

      assertEquals(
          RecognizerIntent.ACTION_RECOGNIZE_SPEECH,
          shadowOf(activity).nextStartedActivityForResult.intent.action,
      )
      launcher.unregister()
    }
  }
}
