package com.sadad.app.a11y

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.history.HistoryScreen
import com.sadad.app.feature.history.HistoryTags
import com.sadad.app.feature.history.HistoryViewModel
import com.sadad.app.feature.history.statementSource
import com.sadad.app.testing.seedDebt
import com.sadad.app.testing.seedPayment
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The movement feed at normal and large font sizes. */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class ActivityA11yTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  @Test fun history() = history(NORMAL_FONT)

  @Test fun historyAtLargestFont() = history(LARGEST_FONT)

  private fun history(fontScale: Float) = compose.withStore { fixture ->
    awaiting {
      seedContacts(fixture)
      val scope = checkNotNull(fixture.graph.current.value)
      val id = scope.repository.view.first { it != null }!!.contacts.first().id
      val debt = scope.seedDebt(id, amount = Cents(10_000))
      scope.seedPayment(debt, Cents(2_000))
      fixture.settleAll()
    }
    val history = HistoryViewModel(fixture.graph.statementSource(), SavedStateHandle())
    fixture.closeWithStore(history.viewModelScope)
    awaiting {
      history.state.first { it.loaded && it.movements.isNotEmpty() }
    }
    compose.showAt(fontScale) {
      HistoryScreen(historyViewModel = history)
    }
    compose.assertAccessible(
        hasTestTag(HistoryTags.ORDER),
        hasTestTag(HistoryTags.PERIOD),
    )
  }
}
