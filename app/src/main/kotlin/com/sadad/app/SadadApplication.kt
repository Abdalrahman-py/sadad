package com.sadad.app

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import androidx.work.Configuration
import com.sadad.app.feature.notifications.DueReminderWorker
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SadadApplication : Application(), Configuration.Provider {
  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().build()

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
      // The platform leaks closeables of its own (a SurfacePackage parcelled in for keyboard
      // autofill suggestions); dying on those hides our own findings, so only a violation that
      // names Sadad code kills the app. Each one is still logged.
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        vmPolicy.penaltyListener(Executors.newSingleThreadExecutor()) { violation ->
          if (namesOurCode(violation)) {
            Handler(Looper.getMainLooper()).post {
              throw RuntimeException("StrictMode VmPolicy violation", violation)
            }
          }
        }
      } else {
        vmPolicy.penaltyDeath()
      }
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

  /** A violation's own stack, its causes' stacks (the allocation site of a leak) or its message. */
  private fun namesOurCode(violation: Throwable): Boolean =
      generateSequence(violation) { it.cause }
          .any { cause ->
            cause.message?.contains(OUR_PACKAGE) == true ||
                cause.stackTrace.any { it.className.startsWith(OUR_PACKAGE) }
          }

  private companion object {
    const val OUR_PACKAGE = "com.sadad."
  }
}
