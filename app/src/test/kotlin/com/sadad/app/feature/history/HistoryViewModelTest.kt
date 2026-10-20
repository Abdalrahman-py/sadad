package com.sadad.app.feature.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sadad.app.data.db.Events
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.PaymentMethod
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class HistoryViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun octoberListsTheTwoContactsWithOctoberMovementsNewestFirst() = runTest {
    TestGraph.signedIn().use { fixture ->
      val sami = fixture.confirmedContact("سامي", at("2026-09-20T09:00:00Z"))
      val laila = fixture.confirmedContact("ليلى", at("2026-09-21T09:00:00Z"))
      val omar = fixture.confirmedContact("عمر", at("2026-09-22T09:00:00Z"))
      fixture.confirmedDebt(sami, 10_000, at("2026-10-02T09:00:00Z"))
      fixture.confirmedDebt(laila, 5_000, at("2026-10-09T09:00:00Z"))
      fixture.confirmedDebt(omar, 7_000, at("2026-09-25T09:00:00Z"))
      val model = HistoryViewModel(fixture.graph, SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)

      model.selectPreset(PeriodPreset.MONTH)
      val october = model.state.first { it.loaded && it.people.size == 2 }
      assertEquals(Period.Month(YearMonth.of(2026, 10)), october.period)
      assertEquals(listOf("ليلى", "سامي"), october.people.map { it.name })

      model.selectPreset(PeriodPreset.ALL)
      val all = model.state.first { it.period == Period.All && it.people.size == 3 }

      assertEquals(listOf("ليلى", "سامي", "عمر"), all.people.map { it.name })
    }
  }

  @Test
  fun theNewestMovementDecidesTheOrderAcrossContacts() = runTest {
    TestGraph.signedIn().use { fixture ->
      val sami = fixture.confirmedContact("سامي", at("2026-10-01T09:00:00Z"))
      fixture.confirmedContact("ليلى", at("2026-10-02T09:00:00Z")).also { laila ->
        fixture.confirmedDebt(laila, 3_000, at("2026-10-02T09:00:00Z"))
      }
      val debt = fixture.confirmedDebt(sami, 10_000, at("2026-10-03T09:00:00Z"))
      fixture.confirmedPayment(debt, 4_000, at("2026-10-08T09:00:00Z"))
      val model = HistoryViewModel(fixture.graph, SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)

      model.selectPreset(PeriodPreset.MONTH)
      val state = model.state.first { it.loaded && it.people.size == 2 }

      // سامي's payment on the 8th is newer than ليلى's debt on the 2nd.
      assertEquals(listOf("سامي", "ليلى"), state.people.map { it.name })
      assertEquals(at("2026-10-08T09:00:00Z"), state.people.first().lastMovementAt)
      assertEquals(Cents(6_000), state.people.first().openBalance)
    }
  }

  @Test
  fun aPersonCarriesTheOpenBalanceAndTheLastDebt() = runTest {
    TestGraph.signedIn().use { fixture ->
      val sami = fixture.confirmedContact("سامي", at("2026-10-01T09:00:00Z"))
      val older = fixture.confirmedDebt(sami, 10_000, at("2026-10-02T09:00:00Z"))
      fixture.confirmedDebt(sami, 5_000, at("2026-10-05T09:00:00Z"))
      fixture.confirmedPayment(older, 4_000, at("2026-10-06T09:00:00Z"))
      val model = HistoryViewModel(fixture.graph, SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)

      model.selectPreset(PeriodPreset.MONTH)
      val person = model.state.first { it.loaded && it.people.isNotEmpty() }.people.single()

      assertEquals(Cents(11_000), person.openBalance)
      assertEquals(Cents(5_000), person.lastDebt)
      assertEquals("سامي", person.name)
    }
  }

  @Test
  fun aPayableMovementDoesNotPutTheContactInTheHistory() = runTest {
    TestGraph.signedIn().use { fixture ->
      val supplier = fixture.confirmedContact("المورد", at("2026-10-01T09:00:00Z"))
      fixture.confirmedDebt(
          supplier,
          3_000,
          at("2026-10-02T09:00:00Z"),
          direction = Direction.PAYABLE,
      )
      val model = HistoryViewModel(fixture.graph, SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)

      model.selectPreset(PeriodPreset.MONTH)
      val state = model.state.first { it.loaded }

      assertEquals(emptyList<HistoryPerson>(), state.people)
    }
  }

  @Test
  fun theChosenTabAndPeriodSurviveARecreatedViewModel() = runTest {
    TestGraph.signedIn().use { fixture ->
      val saved = SavedStateHandle()
      val first = HistoryViewModel(fixture.graph, saved, clock)
      first.selectPreset(PeriodPreset.YEAR)
      first.onTabChange(HistoryTab.ACTIVITY)

      val recreated = HistoryViewModel(fixture.graph, saved, clock)
      val state = recreated.state.first { it.loaded }
      fixture.closeWithStore(recreated.viewModelScope)

      assertEquals(HistoryTab.ACTIVITY, state.tab)
      assertEquals(Period.Year(2026), state.period)
    }
  }

  @Test
  fun aCustomRangeKeepsOnlyTheMovementsInsideIt() = runTest {
    TestGraph.signedIn().use { fixture ->
      val sami = fixture.confirmedContact("سامي", at("2026-10-01T09:00:00Z"))
      fixture.confirmedDebt(sami, 10_000, at("2026-10-17T09:00:00Z"))
      val laila = fixture.confirmedContact("ليلى", at("2026-10-01T09:00:00Z"))
      fixture.confirmedDebt(laila, 5_000, at("2026-10-03T09:00:00Z"))
      val model = HistoryViewModel(fixture.graph, SavedStateHandle(), clock)
      fixture.closeWithStore(model.viewModelScope)

      model.selectCustom(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 17))
      val state = model.state.first { it.loaded && it.people.size == 1 }

      assertEquals(listOf("سامي"), state.people.map { it.name })
    }
  }

  private val clock: Clock =
      Clock.fixed(
          LocalDate.of(2026, 10, 17).atTime(12, 0).atZone(ZoneId.of("Asia/Gaza")).toInstant(),
          ZoneId.of("Asia/Gaza"),
      )

  private fun at(text: String): Instant = Instant.parse(text)

  private suspend fun TestGraph.fold(command: LedgerCommand) {
    val repository = checkNotNull(graph.current.value).repository
    repository.applyPage(Events.page(listOf(Events.accepted(command, ++sequence))))
  }

  private suspend fun TestGraph.confirmedContact(name: String, at: Instant): String {
    val command =
        Commands.contactCreate(
            Events.origin,
            at,
            name = name,
            entityId = UUID.randomUUID().toString(),
        )
    fold(command)
    return command.entityId
  }

  private suspend fun TestGraph.confirmedDebt(
      contactId: String,
      amount: Long,
      at: Instant,
      direction: Direction = Direction.RECEIVABLE,
  ): String {
    val command =
        Commands.debtCreate(
            Events.origin,
            at,
            contactId = contactId,
            direction = direction,
            amount = Cents(amount),
            entityId = UUID.randomUUID().toString(),
        )
    fold(command)
    return command.entityId
  }

  private suspend fun TestGraph.confirmedPayment(debtId: String, amount: Long, at: Instant) {
    fold(
        Commands.paymentCreate(
            Events.origin,
            at,
            debtId = debtId,
            amount = Cents(amount),
            method = PaymentMethod.CASH,
            entityId = UUID.randomUUID().toString(),
        )
    )
  }

  private var sequence = 0L
}
