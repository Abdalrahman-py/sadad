package com.sadad.app.feature.reports

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sadad.app.data.db.Events
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.history.HistoryTags
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.components.formatCents
import com.sadad.app.ui.theme.SadadTheme
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.PaymentMethod
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class ReportsScreenTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun theFourTotalsRenderForTheWholeHistory() {
    show {
      shown(ReportsTags.TOTAL_CONTACTS).assertTextContains("3", substring = true)
      shown(ReportsTags.TOTAL_DEBTS)
          .assertTextContains(formatCents(Cents(22_000)), substring = true)
      shown(ReportsTags.TOTAL_DEBTS).assertTextContains("3 عملية دين", substring = true)
      shown(ReportsTags.TOTAL_PAYMENTS)
          .assertTextContains(formatCents(Cents(3_000)), substring = true)
      shown(ReportsTags.TOTAL_PAYMENTS).assertTextContains("1 حركة سداد", substring = true)
      shown(ReportsTags.OPEN_BALANCE)
          .assertTextContains(formatCents(Cents(19_000)), substring = true)
    }
  }

  @Test
  fun changingThePeriodUpdatesTheTotals() {
    show {
      compose.onNodeWithTag(HistoryTags.PERIOD_MONTH).performClick()
      compose.waitForIdle()

      shown(ReportsTags.TOTAL_CONTACTS).assertTextContains("2", substring = true)
      shown(ReportsTags.TOTAL_DEBTS)
          .assertTextContains(formatCents(Cents(15_000)), substring = true)
      shown(ReportsTags.TOTAL_DEBTS).assertTextContains("2 عملية دين", substring = true)
      shown(ReportsTags.TOTAL_PAYMENTS)
          .assertTextContains(formatCents(Cents(3_000)), substring = true)
      // The open balance is the whole store's, whatever the period.
      shown(ReportsTags.OPEN_BALANCE)
          .assertTextContains(formatCents(Cents(19_000)), substring = true)
    }
  }

  @Test
  fun searchingNarrowsTheContactStatements() {
    show {
      shown(ReportsTags.SEARCH).performTextInput("ليلى")
      compose.waitForIdle()

      shown(ReportsTags.personOpen(laila)).assertIsDisplayed()
      compose.onNodeWithTag(ReportsTags.personOpen(sami)).assertDoesNotExist()
    }
  }

  @Test
  fun eachContactRowOffersTheOpenAndShareActions() {
    show {
      shown(ReportsTags.personOpen(sami)).assertIsDisplayed()
      shown(ReportsTags.personShare(sami)).assertIsDisplayed()
    }
  }

  @Test
  fun theSaveButtonOffersPdfAndExcel() {
    show {
      shown(ReportsTags.SAVE).performClick()
      compose.waitForIdle()

      compose.onNodeWithText("PDF").assertIsDisplayed()
      compose.onNodeWithText("Excel").assertIsDisplayed()
    }
  }

  /** The list composes only the rows on screen, so scroll the node into view before using it. */
  private fun shown(tag: String): SemanticsNodeInteraction {
    compose.onNodeWithTag(ReportsTags.LIST).performScrollToNode(hasTestTag(tag))
    return compose.onNodeWithTag(tag)
  }

  private val sami = "00000000-0000-4000-8000-000000000001"
  private val laila = "00000000-0000-4000-8000-000000000002"
  private val omar = "00000000-0000-4000-8000-000000000003"

  /** Asserts inside the fixture's lifetime: closing the store empties every state flow. */
  private fun show(check: () -> Unit) {
    runBlocking { TestGraph.signedIn() }
        .use { fixture ->
          runBlocking {
            val repository = checkNotNull(fixture.graph.current.value).repository
            var sequence = 0L
            suspend fun fold(command: LedgerCommand) =
                repository.applyPage(Events.page(listOf(Events.accepted(command, ++sequence))))
            suspend fun contact(id: String, name: String) =
                fold(
                    Commands.contactCreate(
                        Events.origin,
                        Instant.parse("2026-09-20T09:00:00Z"),
                        name = name,
                        entityId = id,
                    )
                )
            suspend fun debt(contactId: String, cents: Long, at: String): String {
              val command =
                  Commands.debtCreate(
                      Events.origin,
                      Instant.parse(at),
                      contactId = contactId,
                      direction = Direction.RECEIVABLE,
                      amount = Cents(cents),
                      entityId = UUID.randomUUID().toString(),
                  )
              fold(command)
              return command.entityId
            }
            contact(sami, "سامي")
            contact(laila, "ليلى")
            contact(omar, "عمر")
            debt(omar, 7_000, "2026-09-25T09:00:00Z")
            val samiDebt = debt(sami, 10_000, "2026-10-02T09:00:00Z")
            debt(laila, 5_000, "2026-10-09T09:00:00Z")
            fold(
                Commands.paymentCreate(
                    Events.origin,
                    Instant.parse("2026-10-10T09:00:00Z"),
                    debtId = samiDebt,
                    amount = Cents(3_000),
                    method = PaymentMethod.CASH,
                    entityId = UUID.randomUUID().toString(),
                )
            )
          }
          val model = ReportsViewModel(fixture.graph, SavedStateHandle(), clock)
          fixture.closeWithStore(model.viewModelScope)
          runBlocking { model.state.first { it.loaded } }

          compose.setContent {
            SadadTheme {
              CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                ReportsScreen(reportsViewModel = model)
              }
            }
          }
          compose.waitForIdle()
          check()
        }
  }

  private val clock: Clock =
      Clock.fixed(
          LocalDate.of(2026, 10, 17).atTime(12, 0).atZone(ZoneId.of("Asia/Gaza")).toInstant(),
          ZoneId.of("Asia/Gaza"),
      )
}
