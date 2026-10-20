package com.sadad.app.feature.history

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sadad.app.data.db.Events
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class HistoryScreenTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun anEmptyStoreShowsTheArabicEmptyState() {
    show(seed = false) { history, activity ->
      history.state.first { it.loaded }
      activity.state.first { it.loaded }
    }

    compose.onNodeWithText("السجل فارغ").assertIsDisplayed()
  }

  @Test
  fun switchingTabsKeepsThePeriod() {
    show(seed = true) { history, _ -> history.state.first { it.loaded } }

    compose.onNodeWithTag(HistoryTags.PERIOD_MONTH).performClick()
    compose.onNodeWithTag(HistoryTags.TAB_ACTIVITY).performClick()
    compose.onNodeWithTag(HistoryTags.TAB_PEOPLE).performClick()

    compose.onNodeWithTag(HistoryTags.PERIOD_MONTH).assertIsSelected()
  }

  @Test
  fun theActivityTabShowsTheLedgerActions() {
    show(seed = true) { history, activity ->
      history.state.first { it.loaded }
      activity.state.first { it.entries.isNotEmpty() }
    }

    compose.onNodeWithTag(HistoryTags.TAB_ACTIVITY).performClick()

    compose.onNodeWithText("إضافة شخص").assertIsDisplayed()
    compose.onNodeWithText("تسجيل دين").assertIsDisplayed()
  }

  private fun show(
      seed: Boolean,
      ready: suspend (HistoryViewModel, ActivityLogViewModel) -> Unit,
  ) {
    runBlocking { TestGraph.signedIn() }
        .use { fixture ->
          if (seed) {
            runBlocking {
              val repository = checkNotNull(fixture.graph.current.value).repository
              val contactId = repository.addContact("سامي", null, null, null, null).getOrThrow()
              repository.addDebt(
                  contactId,
                  com.sadad.ledger.Direction.RECEIVABLE,
                  com.sadad.ledger.Cents(10_000),
                  null,
                  null,
              )
              fixture.settleAll()
            }
          }
          val history = HistoryViewModel(fixture.graph, SavedStateHandle(), clock)
          val activity = ActivityLogViewModel(fixture.graph)
          fixture.closeWithStore(history.viewModelScope)
          fixture.closeWithStore(activity.viewModelScope)
          runBlocking { ready(history, activity) }

          compose.setContent {
            SadadTheme {
              CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                HistoryScreen(historyViewModel = history, activityViewModel = activity)
              }
            }
          }
          compose.waitForIdle()
        }
  }

  private val clock: Clock =
      Clock.fixed(
          LocalDate.of(2026, 10, 17).atTime(12, 0).atZone(ZoneId.of("Asia/Gaza")).toInstant(),
          ZoneId.of("Asia/Gaza"),
      )
}
