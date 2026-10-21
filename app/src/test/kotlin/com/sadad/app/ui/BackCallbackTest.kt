package com.sadad.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Predictive back needs the app to opt in, and the app targets API 36 (ticket 68). */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class BackCallbackTest {
  @Test
  fun theMergedManifestOptsInToPredictiveBack() {
    // Robolectric does not expose the flag on ApplicationInfo, so read the manifest AGP merged for
    // the debug unit-test variant (the path comes from the same config file Robolectric reads).
    val config = Properties()
    checkNotNull(
            javaClass.classLoader?.getResourceAsStream("com/android/tools/test_config.properties")
        )
        .use(config::load)
    val manifest = File(config.getProperty("android_merged_manifest")).readText()
    val application = Regex("<application\\b[^>]*>").find(manifest)
    assertTrue(
        "no android:enableOnBackInvokedCallback=\"true\" on <application>",
        application != null && "android:enableOnBackInvokedCallback=\"true\"" in application.value,
    )
  }

  @Test
  fun theAppTargetsApi36() {
    val info = ApplicationProvider.getApplicationContext<Context>().applicationInfo
    assertEquals(36, info.targetSdkVersion)
  }
}
