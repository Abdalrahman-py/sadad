package com.sadad.app.feature.history

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.PaymentMethod
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    show(seed = false) {
      compose.onNodeWithText("السجل فارغ").assertIsDisplayed()
    }
  }

  @Test
  fun theRecentFeedHasTwoFiltersAndNoLedgerOperationsTab() {
    show(seed = true) { history ->
      compose.onNodeWithText("آخر الحركات").assertIsDisplayed()
      compose.onNodeWithText("عمليات الدفتر").assertDoesNotExist()
      compose.onNodeWithTag(HistoryTags.ORDER).assertIsDisplayed()
      compose.onNodeWithTag(HistoryTags.PERIOD).assertIsDisplayed().performClick()
      compose.onNodeWithTag(HistoryTags.PERIOD_MONTH).performClick()
      compose.onNodeWithTag(HistoryTags.ORDER).performClick()
      compose.onNodeWithText("الأقدم أولًا").performClick()
      compose.waitUntil(10000) {
        history.state.value.period is Period.Month && !history.state.value.newestFirst
      }
      compose.onNodeWithTag(HistoryTags.PERIOD).performClick()
      compose.onNodeWithTag(HistoryTags.PERIOD_MONTH).assertIsSelected()
    }
  }

  @Test
  fun aSavedPaymentShowsItsTotalAndOpensThePersonsRecord() {
    var opened: String? = null
    show(seed = true, onOpen = { opened = it }) { history ->
      val row = history.state.value.movements.first()
      compose.onNodeWithText("₪ 150").assertIsDisplayed()
      compose.onNodeWithText("دفعة").assertIsDisplayed()
      compose.onNodeWithTag("history:movement:${row.id}").performClick()
      assertEquals(row.contactId, opened)
      val order = compose.onNodeWithTag(HistoryTags.ORDER).fetchSemanticsNode().boundsInRoot
      val period = compose.onNodeWithTag(HistoryTags.PERIOD).fetchSemanticsNode().boundsInRoot
      assertEquals(order.center.y, period.center.y, 1f)
    }
  }

  @Test
  fun theCustomRangeRequiresDatesAndCanConfirmAPreselectedRange() {
    show(seed = true) { history ->
      compose.onNodeWithTag(HistoryTags.PERIOD).performClick()
      compose.onNodeWithTag(HistoryTags.PERIOD_CUSTOM).performClick()
      compose.onNodeWithText("تم").assertIsNotEnabled()
      compose.onNodeWithText("إلغاء").performClick()
      assertEquals(Period.All, history.state.value.period)
      val range = Period.Custom(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
      history.selectCustom(range.from, range.to)
      compose.waitUntil(10000) { history.state.value.period == range }
      compose.onNodeWithTag(HistoryTags.PERIOD).performClick()
      compose.onNodeWithTag(HistoryTags.PERIOD_CUSTOM).performClick()
      compose.onNodeWithText("تم").assertIsEnabled().performClick()
      compose.onNodeWithTag(HistoryTags.PERIOD).assertIsDisplayed()
      assertEquals(range, history.state.value.period)
    }
  }

  /** Asserts inside the fixture's lifetime: closing the store empties every state flow. */
  private fun show(
      seed: Boolean,
      onOpen: (String) -> Unit = {},
      check: (HistoryViewModel) -> Unit,
  ) {
    runBlocking { TestGraph.signedIn() }
        .use { fixture ->
          if (seed) {
            runBlocking {
              val repository = checkNotNull(fixture.graph.current.value).repository
              val contactId = repository.addContact("سامي", null, null, null, null).getOrThrow()
              repository.addDebt(
                  contactId,
                  Direction.RECEIVABLE,
                  Cents(10_000),
                  null,
                  null,
              )
              repository
                  .addDebt(contactId, Direction.RECEIVABLE, Cents(5000), null, null)
                  .getOrThrow()
              repository.payContact(contactId, Cents(15000), PaymentMethod.CASH, null).getOrThrow()
              fixture.settleAll()
            }
          }
          val history = HistoryViewModel(fixture.graph, SavedStateHandle(), clock)
          fixture.closeWithStore(history.viewModelScope)
          runBlocking {
            history.state.first { it.loaded && (!seed || it.movements.size == 3) }
          }

          compose.setContent {
            SadadTheme {
              CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                HistoryScreen(historyViewModel = history, onOpenContact = onOpen)
              }
            }
          }
          compose.waitForIdle()
          check(history)
        }
  }

  private val clock: Clock =
      Clock.fixed(
          LocalDate.of(2026, 10, 17).atTime(12, 0).atZone(ZoneId.of("Asia/Gaza")).toInstant(),
          ZoneId.of("Asia/Gaza"),
      )
}
