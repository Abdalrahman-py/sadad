package com.sadad.app.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Immediate sends and foreground pulls; WorkManager remains the durable offline queue. */
internal class AutomaticSync(
    private val scope: CoroutineScope,
    private val online: () -> Boolean,
    private val status: (String) -> SyncStatus,
    private val pass: suspend (String) -> Unit,
) {
  private data class Runner(val requests: Channel<Unit>, val job: Job)

  private val runners = mutableMapOf<String, Runner>()

  @Synchronized
  fun request(id: String) {
    if (!online()) return
    val runner =
        runners[id]?.takeIf { it.job.isActive }
            ?: run {
              val requests = Channel<Unit>(Channel.CONFLATED)
              val job = scope.launch {
                for (ignored in requests) {
                  if (!online()) continue
                  try {
                    pass(id)
                  } catch (cancelled: CancellationException) {
                    // Switching stores cancels that store's pass, not this process's future
                    // requests.
                    if (!isActive) throw cancelled
                  }
                }
              }
              Runner(requests, job).also { runners[id] = it }
            }
    // A write during a running pass leaves one follow-up; never cancel an in-flight send.
    runner.requests.trySend(Unit)
  }

  @Synchronized
  fun cancel(id: String) {
    runners.remove(id)?.let {
      it.requests.close()
      it.job.cancel()
    }
  }

  suspend fun whileVisible(stores: Flow<String?>, connectivity: Flow<Boolean>) {
    combine(stores, connectivity) { id, connected -> id.takeIf { connected } }
        .collectLatest { id ->
          if (id == null) return@collectLatest
          while (true) {
            when (val current = status(id)) {
              SyncStatus.Idle,
              SyncStatus.Offline,
              SyncStatus.Retry -> request(id)
              is SyncStatus.Failed -> if (current.retryable) request(id)
              else -> Unit
            }
            delay(10_000)
          }
        }
  }
}
