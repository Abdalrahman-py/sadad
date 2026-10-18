package com.sadad.app.ui

import android.app.Application
import android.os.StrictMode

/**
 * The Compose tests run with this Application instead of `SadadApplication`, whose debug StrictMode
 * death policy kills the synthetic activity the Compose rule launches. Robolectric shares one
 * sandbox across the app's unit tests, so another test's `SadadApplication` can leave the policy
 * set; clearing it again here keeps each test covered.
 */
class UiTestApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().build())
    StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
  }
}
