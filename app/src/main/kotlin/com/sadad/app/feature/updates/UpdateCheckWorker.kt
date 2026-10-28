package com.sadad.app.feature.updates

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sadad.app.BuildConfig
import java.util.concurrent.TimeUnit

/**
 * Asks the release server once a day and notifies. Neither Seal nor Mihon has this step — both only
 * check while the app is open — so someone who stops opening the app never hears about a security
 * release. Failures are not retried: the next day's run is the retry.
 */
class UpdateCheckWorker
private constructor(
    context: Context,
    parameters: WorkerParameters,
    /** Supplied by a test; WorkManager builds the worker with [constructor]. */
    private val supplied: UpdateCenter?,
) : CoroutineWorker(context, parameters) {
  constructor(context: Context, parameters: WorkerParameters) : this(context, parameters, null)

  override suspend fun doWork(): Result {
    if (!UpdateChannel.autoChecks(BuildConfig.BUILD_TYPE)) return Result.success()
    val center = supplied ?: UpdateCenter.get(applicationContext)
    center.restore()
    center.check(force = true)
    if (center.state.value.phase == UpdateState.Phase.AVAILABLE) center.notifyAvailable()
    return Result.success()
  }

  companion object {
    /** WorkManager keeps the schedule across reboots; KEEP leaves an existing one untouched. */
    fun schedule(context: Context) {
      val request =
          PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
              .setInitialDelay(1, TimeUnit.HOURS)
              .setConstraints(
                  Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
              )
              .build()
      WorkManager.getInstance(context)
          .enqueueUniquePeriodicWork("app-updates", ExistingPeriodicWorkPolicy.KEEP, request)
    }
  }
}
