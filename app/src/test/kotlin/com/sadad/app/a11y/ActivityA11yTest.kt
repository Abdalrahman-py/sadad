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
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.PaymentMethod
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
      val repository = checkNotNull(fixture.graph.current.value).repository
      val id = repository.view.first { it != null }!!.contacts.first().id
      val debt = repository.addDebt(id, Direction.RECEIVABLE, Cents(10000), null, null).getOrThrow()
      repository.payDebt(debt, Cents(2000), PaymentMethod.CASH, null).getOrThrow()
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
