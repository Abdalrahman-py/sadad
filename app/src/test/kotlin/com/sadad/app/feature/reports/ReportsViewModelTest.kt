package com.sadad.app.feature.reports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sadad.app.export.Statement
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.history.Period
import com.sadad.app.feature.history.PeriodPreset
import com.sadad.app.feature.history.bounds
import com.sadad.app.testing.TestGraph
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
        val statements =
            Statement.build(fixture.view(), null, bounds.from, bounds.to?.minusNanos(1))

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
      val november =
          model.state.first { it.loaded && it.period == Period.Month(YearMonth.of(2026, 11)) }

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
      fixture.closeWithStore(first.viewModelScope)
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
  fun aCustomRangeThatEndsBeforeItStartsIsRefused() = runTest {
    TestGraph.signedIn().use { fixture ->
      val model = ReportsViewModel(fixture.graph, SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)

      val failure = runCatching {
        model.selectCustom(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 1))
      }

      assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
      assertEquals(Period.All, model.state.value.period)
    }
  }

  @Test
  fun payableDebtsAndPaymentsOnEarlierDebtsFollowTheStatementRule() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.seedOctober()
      val supplier = fixture.confirmedContact("المورد", at("2026-09-20T09:00:00Z"))
      val payable =
          fixture.confirmedDebt(
              supplier,
              9_000,
              at("2026-10-03T09:00:00Z"),
              direction = Direction.PAYABLE,
          )
      fixture.confirmedPayment(payable, 4_000, at("2026-10-04T09:00:00Z"))
      // عمر paid part of his September Debt in October.
      val omar = fixture.view().contacts.first { it.name == "عمر" }
      val septemberDebt = fixture.view().debts.first { it.contactId == omar.id }
      fixture.confirmedPayment(septemberDebt.id, 2_000, at("2026-10-15T09:00:00Z"))
      val model = ReportsViewModel(fixture.graph, SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)

      model.select(Period.Month(OCTOBER))
      val state = model.state.first { it.loaded && it.period == Period.Month(OCTOBER) }

      // The payable Debt and its Payment count nowhere; the Payment on the September Debt does.
      assertEquals(3, state.totals.contacts)
      assertEquals(2, state.totals.debtCount)
      assertEquals(Cents(15_000), state.totals.debtTotal)
      assertEquals(2, state.totals.paymentCount)
      assertEquals(Cents(5_000), state.totals.paymentTotal)
      assertEquals(Cents(17_000), state.totals.openBalance)
      assertEquals(fixture.view().receivable, state.totals.openBalance)
      assertEquals(Cents(5_000), state.people.first { it.name == "عمر" }.openBalance)
      assertEquals(setOf("سامي", "ليلى", "عمر"), state.people.map { it.name }.toSet())
    }
  }
}
