package com.sadad.app.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sadad.app.SadadApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(
    context: Context,
    parameters: WorkerParameters,
    private val pass: suspend (String) -> SyncStatus,
) : CoroutineWorker(context, parameters) {
  constructor(
      context: Context,
      parameters: WorkerParameters,
  ) : this(
      context,
      parameters,
      { id ->
        (context.applicationContext as SadadApplication).graph.syncStore(id)
      },
  )

  override suspend fun doWork(): Result =
      withContext(Dispatchers.IO) {
        val id = inputData.getString(STORE_USER_ID) ?: return@withContext Result.failure()
        when (val status = pass(id)) {
          SyncStatus.Offline,
          SyncStatus.Retry -> Result.retry()
          // A terminal refusal ends the chain instead of retrying it forever.
          is SyncStatus.Failed -> if (status.retryable) Result.retry() else Result.failure()
          else -> Result.success()
        }
      }

  companion object {
    const val STORE_USER_ID = "storeUserId"
  }
}
