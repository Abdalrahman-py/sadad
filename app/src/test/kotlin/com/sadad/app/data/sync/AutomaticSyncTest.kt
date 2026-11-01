package com.sadad.app.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AutomaticSyncTest {
  @Test
  fun reconnectAndResumePullWithoutAWriteAndStopWhenHidden() = runTest {
    var online = false
    val calls = mutableListOf<String>()
    val stores = MutableStateFlow<String?>("a")
    val connected = MutableStateFlow(false)
    val sync = AutomaticSync(backgroundScope, { online }, { SyncStatus.Idle }) { calls += it }
    val visible = backgroundScope.launch { sync.whileVisible(stores, connected) }
    sync.request("a")
    runCurrent()
    assertTrue(calls.isEmpty())
    online = true
    connected.value = true
    runCurrent()
    assertEquals(listOf("a"), calls)
    advanceTimeBy(10_001)
    runCurrent()
    assertEquals(listOf("a", "a"), calls)
    stores.value = "b"
    runCurrent()
    assertEquals("b", calls.last())
    visible.cancelAndJoin()
    val count = calls.size
    advanceTimeBy(30_000)
    runCurrent()
    assertEquals(count, calls.size)
    val resumed = backgroundScope.launch { sync.whileVisible(stores, connected) }
    runCurrent()
    assertEquals(count + 1, calls.size)
    resumed.cancelAndJoin()
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
  fun refusedPermissionsDoNotHammerTheServerEveryTenSeconds() = runTest {
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
  fun aPassThatDiesUnexpectedlyIsClearedAndRetriedInsteadOfCrashing() = runTest {
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
    assertEquals(1, calls)
    assertEquals("a stuck Running status must be cleared", 1, cleared)
    advanceTimeBy(10_001)
    runCurrent()
    assertEquals(2, calls)
  }

  @Test
  fun aRetryableRefusalBacksOffInsteadOfPollingEveryTenSeconds() = runTest {
    val calls = mutableListOf<Long>()
    val sync =
        AutomaticSync(
            backgroundScope,
            { true },
            { SyncStatus.Failed("temporary_service_failure") },
        ) {
          calls += currentTime
        }
    backgroundScope.launch { sync.whileVisible(MutableStateFlow("a"), MutableStateFlow(true)) }
    runCurrent()
    advanceTimeBy(10_001)
    runCurrent()
    advanceTimeBy(10_001)
    runCurrent()
    assertEquals("10s, then 20s, not 10s again", listOf(0L, 10_000L), calls)
    advanceTimeBy(10_001)
    runCurrent()
    assertEquals(listOf(0L, 10_000L, 30_000L), calls)
  }
}
