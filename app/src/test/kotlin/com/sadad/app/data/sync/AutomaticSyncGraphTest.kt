package com.sadad.app.data.sync

import com.sadad.app.data.db.Events
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.STORE_B
import com.sadad.app.testing.seedDebt
import com.sadad.app.testing.seedPayment
import com.sadad.ledger.Cents
import com.sadad.ledger.ChangeEvent
import com.sadad.ledger.LedgerJson
import com.sadad.ledger.OperationsRequest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutomaticSyncGraphTest {
  @Test
  fun offlineEntriesReachAnotherInstallationOnReconnectWithoutManualSync() = runTest {
    var online = false
    SyncFixture.create(scheduler = DeferredScheduler, online = { online }).use { sender ->
      SyncFixture.create(storeUserId = STORE_B).use { receiver ->
        val server = SharedLedger()
        sender.server.dispatcher = server
        receiver.server.dispatcher = server
        val connected = MutableStateFlow(false)
        val sending =
            backgroundScope.launch(Dispatchers.Default) {
              sender.test.graph.syncWhileVisible(connected)
            }
        var receiving: Job? = null
        try {
          val repository = sender.scope.repository
          val person = repository.addContact("إبراهيم", null, null, null, null).getOrThrow()
          val debt = sender.scope.seedDebt(person, amount = Cents(15_000))
          sender.scope.seedPayment(debt, Cents(5_000))
          assertEquals(3, sender.scope.outbox.snapshot().count { it.state == OutboxState.QUEUED })
          assertEquals(1, sender.server.requestCount) // Sign-in only: no offline HTTP request.
          online = true
          connected.value = true
          withContext(Dispatchers.Default) {
            withTimeout(45_000) {
              // The sender's one pass on the network return pushes the offline queue.
              sender.test.graph.accounts.accounts.first { it.first().lastSyncAt != null }
            }
          }
          // The receiver comes to the foreground only after the sender's work is accepted: its
          // visibility start is a trigger and one pass delivers the data. There is no poll.
          receiving =
              backgroundScope.launch(Dispatchers.Default) {
                receiver.test.graph.syncWhileVisible(connected)
              }
          withContext(Dispatchers.Default) {
            withTimeout(45_000) {
              receiver.scope.repository.view.first { view ->
                view != null && view.debts.size == 1 && view.payments.size == 1
              }
              receiver.test.graph.accounts.accounts.first { it.first().lastSyncAt != null }
            }
          }
          assertTrue(sender.scope.outbox.snapshot().all { it.state == OutboxState.ACCEPTED })
          assertEquals("إبراهيم", receiver.scope.repository.view.value!!.contacts.single().name)
          assertNotNull(receiver.test.graph.accounts.account(STORE_B)!!.lastSyncAt)
        } finally {
          sending.cancelAndJoin()
          receiving?.cancelAndJoin()
        }
      }
    }
  }

  @Test
  fun onlyACompleteSuccessfulPassUpdatesTheSavedTime() = runTest {
    SyncFixture.create().use { f ->
      assertNull(f.test.graph.accounts.account(STORE_A)!!.lastSyncAt)
      f.page()
      assertEquals(SyncStatus.Idle, f.test.graph.syncStore(STORE_A))
      val successfulTime = f.now
      assertEquals(successfulTime, f.test.graph.accounts.account(STORE_A)!!.lastSyncAt)
      f.now += 1_000
      f.server.enqueue(refusalResponse("temporary_service_failure", 503))
      assertTrue(f.test.graph.syncStore(STORE_A) is SyncStatus.Failed)
      assertEquals(successfulTime, f.test.graph.accounts.account(STORE_A)!!.lastSyncAt)
      f.page()
      assertEquals(SyncStatus.Idle, f.test.graph.syncStore(STORE_A))
      assertEquals(f.now, f.test.graph.accounts.account(STORE_A)!!.lastSyncAt)
    }
  }

  @Test
  fun savingAnEntrySendsItWithoutManualSyncOrWaitingForWorkManager() = runTest {
    SyncFixture.create(scheduler = DeferredScheduler).use { f ->
      f.server.enqueue(refusalResponse("store_permission_denied"))
      f.queue(1)
      val request = f.server.takeRequest(2, TimeUnit.SECONDS)
      assertNotNull("Saving must immediately reach the server without a manual sync", request)
      assertEquals("/v1/mobile/operations", request!!.target)
    }
  }
}

/**
 * Two isolated installations sharing one server ledger; receipts and pull pages use real commands.
 */
private class SharedLedger : Dispatcher() {
  private val events = mutableListOf<ChangeEvent>()

  @Synchronized
  override fun dispatch(request: RecordedRequest): MockResponse {
    if (request.method == "POST") {
      val commands =
          LedgerJson.decodeFromString(OperationsRequest.serializer(), request.body!!.utf8())
              .operations
      val offset = events.size.toLong()
      events += commands.mapIndexed { index, command ->
        Events.accepted(command, offset + index + 1)
      }
      return receiptResponse(commands, sequenceOffset = offset)
    }
    val after = request.url.queryParameter("after")!!.toLong()
    return pageResponse(
        Events.page(
            events.filter { it.sequence > after },
            cursor = events.size.toLong(),
            nextCursor = events.size.toLong(),
        )
    )
  }
}

private object DeferredScheduler : SyncScheduling {
  override fun syncNow(storeUserId: String) {}

  override fun schedulePeriodic(storeUserId: String) {}

  override fun cancel(storeUserId: String) {}
}
