package com.sadad.app.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutomaticSyncTest {
  @Test
  fun oneRequestWhenVisibleStartsAndNoneMoreAfterTenMinutes() = runTest {
    val calls = mutableListOf<String>()
    val sync = AutomaticSync(backgroundScope, { true }, { SyncStatus.Idle }) { calls += it }
    backgroundScope.launch { sync.whileVisible(MutableStateFlow("a"), MutableStateFlow(true)) }
    runCurrent()
    assertEquals(listOf("a"), calls)
    advanceTimeBy(10 * 60_000)
    runCurrent()
    assertEquals("the radio must sleep while the user only looks", 1, calls.size)
  }

  @Test
  fun returningOnlineRequestsOnceMoreAndASwitchRequestsTheNewStore() = runTest {
    val calls = mutableListOf<String>()
    val stores = MutableStateFlow<String?>("a")
    val connected = MutableStateFlow(false)
    val sync = AutomaticSync(backgroundScope, { true }, { SyncStatus.Idle }) { calls += it }
    backgroundScope.launch { sync.whileVisible(stores, connected) }
    runCurrent()
    assertTrue(calls.isEmpty())
    connected.value = true
    runCurrent()
    assertEquals(listOf("a"), calls)
    connected.value = false
    runCurrent()
    connected.value = true
    runCurrent()
    assertEquals("the network coming back requests once", 2, calls.size)
    stores.value = "b"
    runCurrent()
    assertEquals("b", calls.last())
    advanceTimeBy(10 * 60_000)
    runCurrent()
    assertEquals("no loop behind the switch", 3, calls.size)
  }

  @Test
  fun signedOutRevokedAndRunningAskForNothing() = runTest {
    for (status in listOf(SyncStatus.SignedOut, SyncStatus.Revoked, SyncStatus.Running)) {
      val calls = mutableListOf<String>()
      val sync = AutomaticSync(backgroundScope, { true }, { status }) { calls += it }
      backgroundScope.launch { sync.whileVisible(MutableStateFlow("a"), MutableStateFlow(true)) }
      runCurrent()
      advanceTimeBy(60_000)
      runCurrent()
      assertTrue(status.toString(), calls.isEmpty())
    }
  }

  @Test
  fun writesDuringASendCoalesceWithoutCancellingItOrLosingAnotherStore() = runTest {
    val release = CompletableDeferred<Unit>()
    val calls = mutableListOf<String>()
    val sync =
        AutomaticSync(backgroundScope, { true }, { SyncStatus.Idle }) {
          calls += it
          if (calls.size == 1) release.await()
        }
    sync.request("a")
    runCurrent()
    repeat(20) { sync.request("a") }
    sync.request("b")
    runCurrent()
    assertEquals(listOf("a", "b"), calls)
    assertFalse(release.isCompleted)
    release.complete(Unit)
    runCurrent()
    assertEquals(listOf("a", "b", "a"), calls)
  }

  @Test
  fun aTerminalRefusalNeverRequests() = runTest {
    var calls = 0
    val sync =
        AutomaticSync(backgroundScope, { true }, { SyncStatus.Failed("store_permission_denied") }) {
          calls++
        }
    backgroundScope.launch { sync.whileVisible(MutableStateFlow("a"), MutableStateFlow(true)) }
    advanceTimeBy(30_001)
    runCurrent()
    assertEquals(0, calls)
  }

  @Test
  fun aPassThatDiesUnexpectedlyIsClearedInsteadOfCrashing() = runTest {
    var calls = 0
    var cleared = 0
    val sync =
        AutomaticSync(
            backgroundScope,
            { true },
            { SyncStatus.Failed("temporary_service_failure") },
            { cleared++ },
        ) {
          calls++
          throw IllegalStateException("store scope is closed")
        }
    backgroundScope.launch { sync.whileVisible(MutableStateFlow("a"), MutableStateFlow(true)) }
    runCurrent()
    advanceTimeBy(10 * 60_000)
    runCurrent()
    assertEquals(1, calls)
    assertEquals("a stuck Running status must be cleared", 1, cleared)
  }

  @Test
  fun aRetryableRefusalRequestsOnceAndNeverPolls() = runTest {
    val calls = mutableListOf<String>()
    val sync =
        AutomaticSync(
            backgroundScope,
            { true },
            { SyncStatus.Failed("temporary_service_failure") },
        ) {
          calls += it
        }
    backgroundScope.launch { sync.whileVisible(MutableStateFlow("a"), MutableStateFlow(true)) }
    runCurrent()
    assertEquals(1, calls.size)
    advanceTimeBy(10 * 60_000)
    runCurrent()
    assertEquals("a struggling server is left to WorkManager's backoff", 1, calls.size)
  }
}
