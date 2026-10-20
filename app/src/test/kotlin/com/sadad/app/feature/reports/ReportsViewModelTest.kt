package com.sadad.app.feature.reports

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.db.Events
import com.sadad.app.export.Statement
import com.sadad.app.export.StatementFormat
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.history.Period
import com.sadad.app.feature.history.PeriodPreset
import com.sadad.app.feature.history.bounds
import com.sadad.app.testing.TestGraph
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentMethod
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = com.sadad.app.ui.UiTestApplication::class)
class ReportsViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun octoberTotalsCountTheDebtsAndTheLivePaymentsOfTheMonth() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.seedOctober()
      val model = ReportsViewModel(fixture.graph, SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)

      model.selectPreset(PeriodPreset.MONTH)
      val state = model.state.first { it.loaded && it.period == Period.Month(OCTOBER) }
      val view = fixture.view()

      assertEquals(2, state.totals.contacts)
      assertEquals(2, state.totals.debtCount)
      assertEquals(Cents(15_000), state.totals.debtTotal)
      assertEquals(1, state.totals.paymentCount)
      assertEquals(Cents(3_000), state.totals.paymentTotal)
      assertEquals(view.receivable, state.totals.openBalance)
      assertEquals(Cents(19_000), state.totals.openBalance)
    }
  }

  @Test
  fun theTotalsEqualTheStatementTotalsForTheSamePeriod() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.seedOctober()
      val model = ReportsViewModel(fixture.graph, SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)

      for (period in
          listOf(
              Period.Month(OCTOBER),
              Period.Month(YearMonth.of(2026, 9)),
              Period.Year(2026),
              Period.All,
              Period.Custom(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 10)),
          )) {
        model.select(period)
        val state = model.state.first { it.loaded && it.period == period }
        val bounds = period.bounds(zone)
        val statements = Statement.build(fixture.view(), null, bounds.from, bounds.to?.minusNanos(1))

        assertEquals(
            "debts in $period",
            statements.fold(Cents.ZERO) { sum, it -> sum + it.totalDebt },
            state.totals.debtTotal,
        )
        assertEquals(
            "payments in $period",
            statements.fold(Cents.ZERO) { sum, it -> sum + it.totalPaid },
            state.totals.paymentTotal,
        )
        assertEquals(
            "contacts in $period",
            statements.count { it.rows.isNotEmpty() },
            state.totals.contacts,
        )
      }
    }
  }

  @Test
  fun aMovementAtMidnightBelongsToTheNewDayOnly() = runTest {
    TestGraph.signedIn().use { fixture ->
      val sami = fixture.confirmedContact("سامي", at("2026-09-20T09:00:00Z"))
      // Midnight of 1 November in Asia/Gaza is the end of October and the start of November.
      val boundary = LocalDate.of(2026, 11, 1).atStartOfDay(zone).toInstant()
      fixture.confirmedDebt(sami, 4_000, boundary)
      val model = ReportsViewModel(fixture.graph, SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)

      model.select(Period.Month(OCTOBER))
      val october = model.state.first { it.loaded && it.period == Period.Month(OCTOBER) }
      model.select(Period.Month(YearMonth.of(2026, 11)))
      val november = model.state.first { it.loaded && it.period == Period.Month(YearMonth.of(2026, 11)) }

      assertEquals(Cents.ZERO, october.totals.debtTotal)
      assertEquals(Cents(4_000), november.totals.debtTotal)
    }
  }

  @Test
  fun searchingByNameKeepsTheContactsWhoseNameContainsTheText() = runTest {
    TestGraph.signedIn().use { fixture ->
      for (name in listOf("سامي", "سالم", "مساء", "ليلى", "عمر")) {
        fixture.confirmedContact(name, at("2026-10-01T09:00:00Z"))
      }
      val model = ReportsViewModel(fixture.graph, SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)
      model.state.first { it.loaded && it.people.size == 5 }

      model.onQueryChange("سا")

      val state = model.state.first { it.query == "سا" && it.loaded }
      assertEquals(setOf("سامي", "سالم", "مساء"), state.people.map { it.name }.toSet())
    }
  }

  @Test
  fun searchingArabicIndicPhoneDigitsMatchesTheStoredPhone() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.confirmedContact("سامي", at("2026-10-01T09:00:00Z"), phone = "+970599123456")
      fixture.confirmedContact("ليلى", at("2026-10-01T09:00:00Z"), phone = "0568000111")
      val model = ReportsViewModel(fixture.graph, SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)
      model.state.first { it.loaded && it.people.size == 2 }

      model.onQueryChange("٠٥٩٩")

      val state = model.state.first { it.query == "٠٥٩٩" && it.loaded }
      assertEquals(listOf("سامي"), state.people.map { it.name })
    }
  }

  @Test
  fun aChosenPeriodListsOnlyTheContactsWithMovementsInIt() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.seedOctober()
      val model = ReportsViewModel(fixture.graph, SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)

      model.select(Period.All)
      val all = model.state.first { it.loaded && it.period == Period.All }
      model.select(Period.Month(OCTOBER))
      val october = model.state.first { it.loaded && it.period == Period.Month(OCTOBER) }

      assertEquals(setOf("سامي", "ليلى", "عمر"), all.people.map { it.name }.toSet())
      assertEquals(setOf("سامي", "ليلى"), october.people.map { it.name }.toSet())
    }
  }

  @Test
  fun thePeriodAndTheSearchSurviveARecreatedViewModel() = runTest {
    TestGraph.signedIn().use { fixture ->
      val saved = SavedStateHandle()
      val first = ReportsViewModel(fixture.graph, saved, clock)
      first.selectPreset(PeriodPreset.YEAR)
      first.onQueryChange("سا")

      val recreated = ReportsViewModel(fixture.graph, saved, clock)
      val state = recreated.state.first { it.loaded }
      fixture.closeWithStore(recreated.viewModelScope)

      assertEquals(Period.Year(2026), state.period)
      assertEquals("سا", state.query)
      assertEquals("سا", state.queryInput)
    }
  }

  @Test
  fun savingWritesTheChosenFormatToTheDocumentAndReportsIt() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.seedOctober()
      val model = ReportsViewModel(fixture.graph, SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)
      model.state.first { it.loaded }
      val file = File.createTempFile("report", ".xlsx", fixture.context.cacheDir)
      try {
        model.saveStatement(fixture.context, Uri.fromFile(file), StatementFormat.XLSX)

        val state = model.state.first { it.saved }
        assertTrue(file.length() > 0)
        assertEquals(null, state.error)
        // XLSX is a zip archive.
        assertEquals("PK", file.readBytes().decodeToString(0, 2))
      } finally {
        file.delete()
      }
    }
  }

  @Test
  fun aDocumentThatCannotBeOpenedShowsTheFailureMessage() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.seedOctober()
      val model = ReportsViewModel(fixture.graph, SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)
      model.state.first { it.loaded }

      model.saveStatement(
          fixture.context,
          Uri.parse("content://com.sadad.app.missing/document"),
          StatementFormat.PDF,
      )

      val state = model.state.first { it.error != null }
      assertEquals(fixture.context.getString(com.sadad.app.R.string.reports_export_failed), state.error)
      assertFalse(state.saved)
    }
  }

  @Test
  fun openingAContactStatementLaunchesAPdfViewerWithReadAccess() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.seedOctober()
      val model = ReportsViewModel(fixture.graph, SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)
      val sami = model.state.first { it.loaded && it.people.isNotEmpty() }.people.first { it.name == "سامي" }

      model.openStatement(fixture.context, sami.contactId)

      val launched = awaitStartedActivity()
      assertEquals(Intent.ACTION_VIEW, launched.action)
      assertEquals("application/pdf", launched.type)
      assertTrue(launched.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }
  }

  private fun awaitStartedActivity(): Intent {
    val application = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
    repeat(200) {
      application.nextStartedActivity?.let { return it }
      Thread.sleep(25)
    }
    error("no activity was started")
  }

  private val zone = ZoneId.of("Asia/Gaza")
  private val clock: Clock =
      Clock.fixed(LocalDate.of(2026, 10, 17).atTime(12, 0).atZone(zone).toInstant(), zone)

  private fun at(text: String): Instant = Instant.parse(text)

  private var sequence = 0L

  private suspend fun TestGraph.view(): LedgerView =
      checkNotNull(checkNotNull(graph.current.value).repository.view.first { it != null })

  private suspend fun TestGraph.fold(command: LedgerCommand) {
    val repository = checkNotNull(graph.current.value).repository
    repository.applyPage(Events.page(listOf(Events.accepted(command, ++sequence))))
  }

  private suspend fun TestGraph.confirmedContact(
      name: String,
      at: Instant,
      phone: String? = null,
  ): String {
    val command =
        Commands.contactCreate(
            Events.origin,
            at,
            name = name,
            phone = phone,
            entityId = UUID.randomUUID().toString(),
        )
    fold(command)
    return command.entityId
  }

  private suspend fun TestGraph.confirmedDebt(contactId: String, amount: Long, at: Instant): String {
    val command =
        Commands.debtCreate(
            Events.origin,
            at,
            contactId = contactId,
            direction = Direction.RECEIVABLE,
            amount = Cents(amount),
            entityId = UUID.randomUUID().toString(),
        )
    fold(command)
    return command.entityId
  }

  private suspend fun TestGraph.confirmedPayment(debtId: String, amount: Long, at: Instant): String {
    val command =
        Commands.paymentCreate(
            Events.origin,
            at,
            debtId = debtId,
            amount = Cents(amount),
            method = PaymentMethod.CASH,
            entityId = UUID.randomUUID().toString(),
        )
    fold(command)
    return command.entityId
  }

  /**
   * October: Debts 100 ₪ (سامي) and 50 ₪ (ليلى), a Payment of 30 ₪ and a Payment of 10 ₪ that is
   * then reversed. September: a Debt of 70 ₪ (عمر). Open balance 100 + 50 + 70 - 30 = 190 ₪.
   */
  private suspend fun TestGraph.seedOctober() {
    val sami = confirmedContact("سامي", at("2026-09-20T09:00:00Z"))
    val laila = confirmedContact("ليلى", at("2026-09-21T09:00:00Z"))
    val omar = confirmedContact("عمر", at("2026-09-22T09:00:00Z"))
    confirmedDebt(omar, 7_000, at("2026-09-25T09:00:00Z"))
    val samiDebt = confirmedDebt(sami, 10_000, at("2026-10-02T09:00:00Z"))
    val lailaDebt = confirmedDebt(laila, 5_000, at("2026-10-09T09:00:00Z"))
    confirmedPayment(samiDebt, 3_000, at("2026-10-10T09:00:00Z"))
    val reversed = confirmedPayment(lailaDebt, 1_000, at("2026-10-11T09:00:00Z"))
    fold(
        Commands.paymentReverse(
            Events.origin,
            at("2026-10-12T09:00:00Z"),
            paymentId = reversed,
            reason = "خطأ",
        )
    )
  }

  private companion object {
    val OCTOBER: YearMonth = YearMonth.of(2026, 10)
  }
}
