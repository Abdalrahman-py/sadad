package com.sadad.app.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation.compose.rememberNavController
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.theme.SadadTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The dot on the المتجر tab, read from the open store's outbox and sync status. */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class SyncDotTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun aQueuedEntryShowsThePendingDot() =
      withStore(queued = true, SyncDot.PENDING, "المتجر، إدخالات بانتظار الإرسال")

  @Test
  fun anEntryThatCannotBeSentShowsTheStuckDot() =
      withStore(queued = false, SyncDot.STUCK, "المتجر، إدخالات لا يمكن إرسالها", stuck = true)

  @Test fun nothingWaitingShowsNoDot() = withStore(queued = false, SyncDot.NONE, "المتجر")

  private fun withStore(
      queued: Boolean,
      expected: SyncDot,
      description: String,
      stuck: Boolean = false,
  ) {
    runBlocking { withTimeout(10_000) { TestGraph.signedIn() } }
        .use { fixture ->
          if (queued || stuck)
              runBlocking {
                fixture.seed { addContact("سامي", null, null, null, null).getOrThrow() }
              }
          if (stuck)
              runBlocking {
                val scope = checkNotNull(fixture.graph.current.value)
                scope.outbox.markUnsendable(scope.outbox.snapshot().map { it.operationId })
              }
          // The fixture cancels this collector before it closes the database, so no Room query
          // runs against a closed pool.
          val collector =
              CoroutineScope(SupervisorJob() + Dispatchers.Default).also {
                fixture.closeWithStore(it)
              }
          val dot = syncDot(fixture.graph).stateIn(collector, SharingStarted.Eagerly, SyncDot.NONE)
          compose.setContent {
            SadadTheme {
              val shown by dot.collectAsState()
              SadadBottomBar(rememberNavController(), shown)
            }
          }
          compose.waitUntil(5_000) { dot.value == expected }
          compose.onNodeWithTag(ScreenTag.NAV_STORE).assertContentDescriptionEquals(description)
        }
  }
}
