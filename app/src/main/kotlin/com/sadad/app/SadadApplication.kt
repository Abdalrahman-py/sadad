package com.sadad.app

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.work.Configuration
import com.sadad.app.crash.CrashReporting
import com.sadad.app.feature.notifications.DueReminderWorker
import com.sadad.app.feature.updates.UpdateCenter
import com.sadad.app.feature.updates.UpdateCheckWorker
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

open class SadadApplication : Application(), Configuration.Provider {
  override val workManagerConfiguration: Configuration
    get() = Configuration.Builder().build()

  private val reminderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  /**
   * One graph for the process; the first access may touch the Keystore, so callers do it off main.
   */
  private val graphLazy = lazy { createGraph() }
  val graph: AppGraph by graphLazy

  /** The graph when an earlier caller built it, else null; never builds it, so main may ask. */
  val graphIfBuilt: AppGraph?
    get() = if (graphLazy.isInitialized()) graphLazy.value else null

  /** The seam the composition test uses to see which thread builds the graph. */
  internal open fun createGraph(): AppGraph = AppGraph.create(this)

  override fun onCreate() {
    super.onCreate()
    CrashReporting.init(this)
    if (BuildConfig.DEBUG) {
      // The OEM layer reads disk on the main thread inside Activity.onCreate (MIUI's font settings
      // do), so a thread violation kills the app only when its blocking call comes from Sadad code.
      // Each one is still logged.
      val threadPolicy = StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog()
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        threadPolicy.penaltyListener(Executors.newSingleThreadExecutor()) { violation ->
          if (blockingCallIsOurs(violation)) {
            Handler(Looper.getMainLooper()).post {
              throw RuntimeException("StrictMode ThreadPolicy violation", violation)
            }
          }
        }
      } else {
        threadPolicy.penaltyDeath()
      }
      StrictMode.setThreadPolicy(threadPolicy.build())
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
    // WorkManager schedules both workers. A process where it cannot start (a JVM test sandbox has
    // no process name) must not take the uncaught-exception handler with it: log and retry next
    // launch. Nothing here is on the critical path of the app opening.
    reminderScope.launch {
      runCatching { DueReminderWorker.schedule(this@SadadApplication) }
          .onFailure { Log.w(TAG, "Due reminders not scheduled", it) }
      runCatching { UpdateCheckWorker.schedule(this@SadadApplication) }
          .onFailure { Log.w(TAG, "Update check not scheduled", it) }
    }
    // The launch check: throttled inside, and skipped entirely for a local debug build.
    reminderScope.launch {
      runCatching { UpdateCenter.get(this@SadadApplication).refreshIfStale() }
          .onFailure { Log.w(TAG, "Launch update check did not start", it) }
    }
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
    const val TAG = "SadadApplication"
  }
}

/** The graph for the screens under `SadadApp`, which shows no screen until it is built. */
val LocalGraph = staticCompositionLocalOf<AppGraph?> { null }

/** [LocalGraph] for a screen's ViewModel factory; the screen is always under `SadadApp`. */
@Composable
fun requireGraph(): AppGraph = checkNotNull(LocalGraph.current) { "no graph: not under SadadApp" }

/**
 * The process graph, null until built. The first build can touch the Keystore, so it runs on IO,
 * never in composition; a graph an earlier caller built shows on the first frame.
 */
@Composable
fun rememberGraph(): AppGraph? {
  val app = LocalContext.current.applicationContext as? SadadApplication ?: return null
  return produceState(app.graphIfBuilt, app) { value = withContext(Dispatchers.IO) { app.graph } }
      .value
}

private const val SADAD_PACKAGE = "com.sadad."
private val PASS_THROUGH =
    listOf(
        "java.",
        "javax.",
        "libcore.",
        "dalvik.",
        "sun.",
        "android.",
        "com.android.",
        "androidx.",
        "kotlin.",
        "kotlinx.",
    )

/**
 * The frame that made the blocking call is the topmost one outside the JDK, the platform and the
 * androidx and Kotlin libraries those pass through. Frames below it only say who started the
 * activity or message, which is every Sadad launch.
 */
internal fun blockingCallIsOurs(violation: Throwable): Boolean =
    violation.stackTrace
        .firstOrNull { frame -> PASS_THROUGH.none { frame.className.startsWith(it) } }
        ?.className
        ?.startsWith(SADAD_PACKAGE) == true
