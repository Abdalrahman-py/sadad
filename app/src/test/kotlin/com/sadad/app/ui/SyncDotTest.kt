package com.sadad.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.theme.SadadTheme
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
      withStore(queued = true) {
        compose
            .onNodeWithTag(ScreenTag.NAV_STORE)
            .assertContentDescriptionEquals("المتجر، إدخالات بانتظار الإرسال")
      }

  @Test
  fun anEntryThatCannotBeSentShowsTheStuckDot() =
      withStore(queued = false, stuck = true) {
        compose
            .onNodeWithTag(ScreenTag.NAV_STORE)
            .assertContentDescriptionEquals("المتجر، إدخالات لا يمكن إرسالها")
      }

  @Test
  fun nothingWaitingShowsNoDot() =
      withStore(queued = false) {
        compose.onNodeWithTag(ScreenTag.NAV_STORE).assertContentDescriptionEquals("المتجر")
      }

  private fun withStore(queued: Boolean, stuck: Boolean = false, check: () -> Unit) {
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
          compose.setContent {
            SadadTheme {
              val dot by syncDot(fixture.graph).collectAsStateWithLifecycle(SyncDot.NONE)
              SadadBottomBar(rememberNavController(), dot)
            }
          }
          compose.waitForIdle()
          check()
          // Leave the composition before the fixture closes the store under it.
          compose.waitForIdle()
        }
  }
}
