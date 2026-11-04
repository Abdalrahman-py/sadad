package com.sadad.app.ui

import android.os.Build
import android.view.Window
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Keeps the ledger out of the recents picture on API 26–32: the picture is taken after the activity
 * pauses, so the window goes secure exactly while the app is not in front. API 33+ turns the
 * recents picture off itself (`setRecentsScreenshotEnabled`), and a session-long FLAG_SECURE would
 * block the screenshots the owner is allowed to send (#177).
 */
internal class RecentsPrivacy(private val window: Window) : DefaultLifecycleObserver {
  override fun onPause(owner: LifecycleOwner) {
    if (Build.VERSION.SDK_INT < 33) window.setFlags(FLAG_SECURE, FLAG_SECURE)
  }

  override fun onResume(owner: LifecycleOwner) {
    if (Build.VERSION.SDK_INT < 33) window.clearFlags(FLAG_SECURE)
  }

  companion object {
    fun attach(activity: ComponentActivity) {
      activity.lifecycle.addObserver(RecentsPrivacy(activity.window))
    }
  }
}
