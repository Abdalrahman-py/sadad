package com.sadad.app.feature.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sadad.app.data.db.Events
import com.sadad.app.export.MovementKind
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.DebtOrigin
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.PaymentMethod
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class HistoryViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  private fun model(fixture: TestGraph, saved: SavedStateHandle = SavedStateHandle()) =
      HistoryViewModel(fixture.graph.statementSource(), saved, clock).also {
        fixture.closeWithStore(it.viewModelScope)
      }

  @Test
  fun everyDebtAndPaymentIsListedAcrossPeopleInBothOrders() = runTest {
    TestGraph.signedIn().use { fixture ->
      val sami = fixture.confirmedContact("سامي", at("2026-10-01T09:00:00Z"))
      val laila = fixture.confirmedContact("ليلى", at("2026-10-01T10:00:00Z"))
      val first = fixture.confirmedDebt(sami, 10000, at("2026-10-02T09:00:00Z"))
      fixture.confirmedDebt(laila, 3000, at("2026-10-04T09:00:00Z"))
      fixture.confirmedDebt(sami, 5000, at("2026-10-05T09:00:00Z"))
      fixture.confirmedPayment(first, 4000, at("2026-10-06T09:00:00Z"))
      val model = model(fixture)
      val newest = model.state.first { it.loaded && it.movements.size == 4 }
      assertEquals(listOf("سامي", "سامي", "ليلى", "سامي"), newest.movements.map { it.name })
      assertEquals(listOf(4000L, 5000L, 3000L, 10000L), newest.movements.map { it.amount.value })
      assertEquals(
          listOf(MovementKind.PAYMENT, MovementKind.DEBT, MovementKind.DEBT, MovementKind.DEBT),
          newest.movements.map { it.kind },
      )
      model.setNewestFirst(false)
      val oldest = model.state.first { !it.newestFirst && it.loaded }
      assertEquals(newest.movements.reversed(), oldest.movements)
    }
  }

  @Test
  fun dayMonthYearAllAndCustomPeriodsFilterIndividualMovements() = runTest {
    TestGraph.signedIn().use { fixture ->
      val sami = fixture.confirmedContact("سامي", at("2025-01-01T09:00:00Z"))
      for (date in listOf("2025-12-20", "2026-01-02", "2026-10-02", "2026-10-17")) {
        fixture.confirmedDebt(sami, 1000, at(date + "T09:00:00Z"))
      }
      val model = model(fixture)
      model.state.first { it.loaded && it.movements.size == 4 }
      for ((preset, count) in
          listOf(
              PeriodPreset.DAY to 1,
              PeriodPreset.MONTH to 2,
              PeriodPreset.YEAR to 3,
              PeriodPreset.ALL to 4,
          )) {
        model.selectPreset(preset)
        val state = model.state.first { it.loaded && it.period.preset == preset }
        assertEquals(count, state.movements.size)
      }
      model.selectCustom(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 17))
      val custom = model.state.first { it.loaded && it.period is Period.Custom }
      assertEquals(2, custom.movements.size)
      model.selectCustom(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 17))
      assertEquals(
          1,
          model.state
              .first {
                it.loaded &&
                    it.period ==
                        Period.Custom(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 17))
              }
              .movements
              .size,
      )
    }
  }

  @Test
  fun orderAndCustomPeriodSurviveViewModelRecreation() = runTest {
    TestGraph.signedIn().use { fixture ->
      val saved = SavedStateHandle()
      val first = model(fixture, saved)
      val from = LocalDate.of(2026, 10, 2)
      val to = LocalDate.of(2026, 10, 17)
      first.selectCustom(from, to)
      first.setNewestFirst(false)
      val recreated = model(fixture, saved).state.first { it.loaded }
      assertEquals(Period.Custom(from, to), recreated.period)
      assertFalse(recreated.newestFirst)
    }
  }

  @Test
  fun onePaymentAcrossDebtsIsOnePendingThenConfirmedRecord() = runTest {
    TestGraph.signedIn().use { fixture ->
      val sami = fixture.confirmedContact("سامي", at("2026-10-01T09:00:00Z"))
      fixture.confirmedDebt(sami, 10000, at("2026-10-02T09:00:00Z"))
      fixture.confirmedDebt(sami, 5000, at("2026-10-03T09:00:00Z"))
      fixture.seed { payContact(sami, Cents(15000), PaymentMethod.CASH, null).getOrThrow() }
      val model = model(fixture)
      val pending =
          model.state.first {
            it.loaded && it.movements.any { row -> row.kind == MovementKind.PAYMENT }
          }
      assertEquals(3, pending.movements.size)
      assertEquals(
          Cents(15000),
          pending.movements.single { it.kind == MovementKind.PAYMENT }.amount,
      )
      assertTrue(pending.movements.single { it.kind == MovementKind.PAYMENT }.pending)
      fixture.settleAll()
      val confirmed = model.state.first { it.loaded && it.movements.none { row -> row.pending } }
      assertEquals(1, confirmed.movements.count { it.kind == MovementKind.PAYMENT })
    }
  }

  @Test
  fun supplierDebtsAndPaymentsAreIncludedButReversedPaymentsAreNot() = runTest {
    TestGraph.signedIn().use { fixture ->
      val supplier = fixture.confirmedContact("المورد", at("2026-10-01T09:00:00Z"))
      val debt =
          fixture.confirmedDebt(supplier, 10000, at("2026-10-02T09:00:00Z"), Direction.PAYABLE)
      val paid =
          Commands.paymentCreate(
              Events.origin,
              at("2026-10-03T09:00:00Z"),
              debt,
              Cents(4000),
              PaymentMethod.CASH,
          )
      fixture.fold(paid)
      val model = model(fixture)
      val state = model.state.first { it.loaded && it.movements.size == 2 }
      assertTrue(state.movements.all { it.direction == Direction.PAYABLE })
      fixture.fold(
          Commands.paymentReverse(Events.origin, at("2026-10-04T09:00:00Z"), paid.entityId, "خطأ")
      )
      val reversed = model.state.first { it.loaded && it.movements.size == 1 }
      assertEquals(MovementKind.DEBT, reversed.movements.single().kind)
    }
  }

  @Test
  fun excessCreditBelongsToTheSavedPaymentAndCreditAloneIsAlsoAPayment() = runTest {
    TestGraph.signedIn().use { fixture ->
      val sami = fixture.confirmedContact("سامي", at("2026-10-01T09:00:00Z"))
      val first = fixture.confirmedDebt(sami, 10000, at("2026-10-02T09:00:00Z"))
      val second = fixture.confirmedDebt(sami, 5000, at("2026-10-03T09:00:00Z"))
      val paidAt = at("2026-10-04T09:00:00Z")
      fixture.confirmedPayment(first, 10000, paidAt)
      fixture.confirmedPayment(second, 5000, paidAt)
      fixture.fold(
          Commands.debtCreate(
              Events.origin,
              paidAt,
              sami,
              Direction.PAYABLE,
              Cents(5000),
              debtOrigin = DebtOrigin.OVERPAYMENT,
          )
      )
      val creditAt = at("2026-10-05T09:00:00Z")
      fixture.fold(
          Commands.debtCreate(
              Events.origin,
              creditAt,
              sami,
              Direction.PAYABLE,
              Cents(1000),
              debtOrigin = DebtOrigin.OVERPAYMENT,
          )
      )
      val state = model(fixture).state.first { it.loaded && it.movements.size == 4 }
      val payments = state.movements.filter { it.kind == MovementKind.PAYMENT }
      assertEquals(listOf(Cents(1000), Cents(20000)), payments.map { it.amount })
      assertEquals(2, state.movements.count { it.kind == MovementKind.DEBT })
    }
  }

  @Test
  fun localDayStartsInclusiveAndEndsExclusive() = runTest {
    TestGraph.signedIn().use { fixture ->
      val sami = fixture.confirmedContact("سامي", at("2026-10-01T09:00:00Z"))
      val day = LocalDate.of(2026, 10, 17)
      val start = day.atStartOfDay(clock.zone).toInstant()
      val end = day.plusDays(1).atStartOfDay(clock.zone).toInstant()
      for (instant in listOf(start.minusMillis(1), start, end.minusMillis(1), end)) {
        fixture.confirmedDebt(sami, 1000, instant)
      }
      val model = model(fixture)
      model.selectPreset(PeriodPreset.DAY)
      val state = model.state.first { it.loaded && it.period is Period.Day }
      assertEquals(listOf(end.minusMillis(1), start), state.movements.map { it.createdAt })
    }
  }

  // The key rule of ticket 165: the list from the period's rows equals the whole-store one.
  @Test
  fun everyPeriodListsWhatTheWholeStoreViewListsInBothOrders() = runTest {
    TestGraph.signedIn().use { fixture ->
      val sami = fixture.confirmedContact("سامي", at("2025-12-01T09:00:00Z"))
      val laila = fixture.confirmedContact("ليلى", at("2025-12-01T10:00:00Z"))
      val gone = fixture.confirmedContact("محذوف", at("2025-12-01T11:00:00Z"))
      val old = fixture.confirmedDebt(sami, 10000, at("2025-12-20T09:00:00Z"))
      val recent = fixture.confirmedDebt(sami, 5000, at("2026-10-02T09:00:00Z"))
      val hers = fixture.confirmedDebt(laila, 3000, at("2026-10-03T09:00:00Z"))
      // One saved payment split over an old and a new debt, with its overpayment credit.
      val splitAt = at("2026-10-10T09:00:00Z")
      fixture.confirmedPayment(old, 3000, splitAt)
      fixture.confirmedPayment(recent, 2000, splitAt)
      fixture.fold(
          Commands.debtCreate(
              Events.origin,
              splitAt,
              sami,
              Direction.PAYABLE,
              Cents(500),
              debtOrigin = DebtOrigin.OVERPAYMENT,
          )
      )
      val reversed =
          Commands.paymentCreate(
              Events.origin,
              at("2026-10-11T09:00:00Z"),
              hers,
              Cents(1000),
              PaymentMethod.CASH,
          )
      fixture.fold(reversed)
      fixture.fold(
          Commands.paymentReverse(
              Events.origin,
              at("2026-10-12T09:00:00Z"),
              reversed.entityId,
              "خطأ",
          )
      )
      // A Debt corrected to zero was deleted.
      val deleted = fixture.confirmedDebt(laila, 700, at("2026-10-13T09:00:00Z"))
      fixture.fold(
          Commands.debtCorrect(
              Events.origin,
              at("2026-10-13T10:00:00Z"),
              deleted,
              expectedVersion = 1,
              amount = Cents.ZERO,
              reason = "حذف",
          )
      )
      fixture.confirmedDebt(gone, 900, at("2026-10-14T09:00:00Z"))
      fixture.fold(
          Commands.contactArchive(
              Events.origin,
              at("2026-10-14T10:00:00Z"),
              gone,
              expectedVersion = 1,
              reason = "حذف",
          )
      )
      fixture.confirmedPayment(old, 1000, at("2026-10-17T07:00:00Z"))
      // Queued: a debt, and a payment on a debt from before every bounded period.
      val scope = checkNotNull(fixture.graph.current.value)
      scope.outbox.append(
          listOf(
              Commands.debtCreate(
                  Events.origin,
                  at("2026-10-17T08:00:00Z"),
                  laila,
                  Direction.RECEIVABLE,
                  Cents(1200),
              ),
              Commands.paymentCreate(
                  Events.origin,
                  at("2026-10-17T08:30:00Z"),
                  old,
                  Cents(400),
                  PaymentMethod.CASH,
              ),
          )
      )
      val whole =
          checkNotNull(
              scope.repository.view.first { it?.pendingDebts == 1 && it.pendingPayments == 1 }
          )
      assertEquals(
          "a statement reads the whole store from Room",
          whole,
          fixture.graph.statementSource().latest()?.view,
      )
      val model = model(fixture)

      val periods =
          listOf(
              periodOf(PeriodPreset.DAY, LocalDate.now(clock)),
              periodOf(PeriodPreset.MONTH, LocalDate.now(clock)),
              periodOf(PeriodPreset.YEAR, LocalDate.now(clock)),
              Period.All,
              Period.Custom(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 13)),
          )
      for (period in periods) {
        model.select(period)
        for (newestFirst in listOf(true, false)) {
          model.setNewestFirst(newestFirst)
          val state =
              model.state.first {
                it.loaded && it.period == period && it.newestFirst == newestFirst
              }
          assertEquals(
              "$period newestFirst=$newestFirst",
              historyMovements(whole, period.bounds(clock.zone), newestFirst),
              state.movements,
          )
        }
      }
      model.select(periods[0])
      model.setNewestFirst(true)
      val today = model.state.first { it.loaded && it.period == periods[0] && it.newestFirst }
      assertEquals(listOf(400L, 1200L, 1000L), today.movements.map { it.amount.value })
      assertEquals(listOf(true, true, false), today.movements.map { it.pending })
      model.select(Period.All)
      val all = model.state.first { it.loaded && it.period == Period.All }
      assertTrue(all.movements.none { it.amount == Cents.ZERO })
      assertTrue(all.movements.none { it.id == reversed.entityId || it.name == "محذوف" })
      assertEquals(1, all.movements.count { it.amount == Cents(5500) })
    }
  }

  @Test
  fun theListNeverAsksForTheWholeStoreView() = runTest {
    TestGraph.signedIn().use { fixture ->
      val sami = fixture.confirmedContact("سامي", at("2026-10-01T09:00:00Z"))
      fixture.confirmedDebt(sami, 1000, at("2026-10-17T07:00:00Z"))
      val graph = fixture.graph.statementSource()
      val asked = mutableListOf<PeriodBounds>()
      val source =
          object : StatementSource {
            override fun view(bounds: PeriodBounds) = graph.view(bounds).also { asked += bounds }

            override suspend fun latest(): Latest? = error("the list reads only its period")
          }
      val model =
          HistoryViewModel(source, SavedStateHandle(), clock).also {
            fixture.closeWithStore(it.viewModelScope)
          }

      model.selectPreset(PeriodPreset.DAY)

      val state = model.state.first { it.loaded && it.period is Period.Day }
      assertEquals(1, state.movements.size)
      assertEquals(state.period.bounds(clock.zone), asked.last())
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
    val before = repository.view.first { it != null }
    repository.applyPage(Events.page(listOf(Events.accepted(command, ++sequence))))
    // The view is projected on another thread; wait until it shows this command.
    repository.view.first { it != before }
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
