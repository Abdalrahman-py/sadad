package com.sadad.app.testing

import com.sadad.app.ui.UiTestApplication
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class TestGraphTest {
  @Test
  fun ownedCollectorsFinishBeforeTheDatabaseCloses() = runTest {
    val fixture = TestGraph.signedIn()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    fixture.closeWithStore(scope)
    val readDuringCleanup = AtomicBoolean(false)
    try {
      val outbox = checkNotNull(fixture.graph.current.value).outbox
      val started = CompletableDeferred<Unit>()
      scope.launch {
        try {
          started.complete(Unit)
          awaitCancellation()
        } finally {
          withContext(NonCancellable) {
            outbox.snapshot()
            readDuringCleanup.set(true)
          }
        }
      }
      started.await()
    } finally {
      fixture.close()
    }

    assertFalse(scope.isActive)
    assertTrue(readDuringCleanup.get())
  }
}
