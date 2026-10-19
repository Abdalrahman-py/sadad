package com.sadad.app

import android.app.Application
import android.os.Build
import android.os.StrictMode
import com.sadad.app.feature.notifications.DueReminderWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SadadApplication : Application() {
  private val reminderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  /**
   * One graph for the process; the first access may touch the Keystore, so callers do it off main.
   */
  val graph: AppGraph by lazy { AppGraph.create(this) }

  override fun onCreate() {
    super.onCreate()
    if (BuildConfig.DEBUG) {
      StrictMode.setThreadPolicy(
          StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().penaltyDeath().build()
      )
      // Spell out VM checks because detectAll also flags OkHttp's untagged sockets.
      val vmPolicy =
          StrictMode.VmPolicy.Builder()
              .detectLeakedSqlLiteObjects()
              .detectActivityLeaks()
              .detectLeakedClosableObjects()
              .detectLeakedRegistrationObjects()
              .detectFileUriExposure()
              .detectCleartextNetwork()
              .detectContentUriWithoutPermission()
              .penaltyLog()
              .penaltyDeath()
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        vmPolicy.detectCredentialProtectedWhileLocked()
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        vmPolicy.detectIncorrectContextUse()
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        vmPolicy.detectUnsafeIntentLaunch()
      }
      StrictMode.setVmPolicy(vmPolicy.build())
    }
    reminderScope.launch { DueReminderWorker.schedule(this@SadadApplication) }
  }
}
