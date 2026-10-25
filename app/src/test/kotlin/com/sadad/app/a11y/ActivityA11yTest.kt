package com.sadad.app.a11y

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.history.ActivityLogViewModel
import com.sadad.app.feature.history.HistoryScreen
import com.sadad.app.feature.history.HistoryTags
import com.sadad.app.feature.history.HistoryViewModel
import com.sadad.app.feature.reports.ReportPerson
import com.sadad.app.feature.reports.ReportsContent
import com.sadad.app.feature.reports.ReportsState
import com.sadad.app.feature.reports.ReportsTags
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The الحركات tab (today's history body) and the reports it absorbs in ticket 85, at both font
 * sizes (ticket 76).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class ActivityA11yTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  @Test fun history() = history(NORMAL_FONT)

  @Test fun historyAtLargestFont() = history(LARGEST_FONT)

  @Test fun reports() = reports(NORMAL_FONT)

  @Test fun reportsAtLargestFont() = reports(LARGEST_FONT)

  private fun history(fontScale: Float) = compose.withStore { fixture ->
    awaiting { seedContacts(fixture) }
    val history = HistoryViewModel(fixture.graph, SavedStateHandle())
    val activity = ActivityLogViewModel(fixture.graph)
    fixture.closeWithStore(history.viewModelScope)
    fixture.closeWithStore(activity.viewModelScope)
    awaiting {
      history.state.first { it.loaded }
      activity.state.first { it.loaded }
    }
    compose.showAt(fontScale) {
      HistoryScreen(historyViewModel = history, activityViewModel = activity)
    }
    compose.assertAccessible(
        hasTestTag(HistoryTags.TAB_PEOPLE),
        hasTestTag(HistoryTags.TAB_ACTIVITY),
        hasTestTag(HistoryTags.PERIOD_CUSTOM),
    )
  }

  private fun reports(fontScale: Float) {
    compose.showAt(fontScale) {
      ReportsContent(
          state =
              ReportsState(
                  loaded = true,
                  people = (1..8).map { ReportPerson("c$it", "شخص $it", "", Cents(100L * it)) },
                  registered = 8,
              ),
          onPreset = {},
          onCustom = { _, _ -> },
          onQueryChange = {},
          onOpen = {},
          onShare = {},
          onSave = {},
      )
    }
    compose.assertAccessible(hasTestTag(ReportsTags.SAVE))
  }
}
