package com.sadad.app.feature.home

import com.sadad.app.export.MovementKind
import com.sadad.ledger.Cents
import com.sadad.ledger.ContactView
import com.sadad.ledger.DebtView
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.PaymentView
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUiStateTest {
  private val zone: ZoneId = ZoneId.of("Asia/Jerusalem")
  private val october: YearMonth = YearMonth.of(2026, 10)

  @Test
  fun totalReceivableIsTheViewsReceivable() {
    val view = ledger(debts = listOf(debt("d1", "c1", 4_500, at(2026, 10, 2))), receivable = 4_500)

    val state = homeUiState(view, october, zone)

    assertEquals(Cents(4_500), state.totalReceivable)
  }

  @Test
  fun monthDebtsUseTheDeviceZone() {
    val view =
        ledger(
            debts =
                listOf(
                    debt("september", "c1", 700, instantAt(2026, 9, 30, 23, 59)),
                    debt("october", "c1", 1_200, instantAt(2026, 10, 1, 0, 0)),
                ),
            receivable = 1_900,
        )

    val state = homeUiState(view, october, zone)

    assertEquals(Cents(1_200), state.monthDebts)
  }

  @Test
  fun reversedPaymentsAreNotCounted() {
    val view =
        ledger(
            debts = listOf(debt("d1", "c1", 3_000, at(2026, 10, 2))),
            payments =
                listOf(
                    payment("reversed", "d1", "c1", 1_000, at(2026, 10, 3), reversed = true),
                    payment("kept", "d1", "c1", 1_000, at(2026, 10, 4)),
                ),
            receivable = 3_000,
        )

    val state = homeUiState(view, october, zone)

    assertEquals(Cents(1_000), state.monthPayments)
  }

  @Test
  fun paymentOnAPayableDebtIsNotCounted() {
    val view =
        ledger(
            debts = listOf(debt("d1", "c1", 3_000, at(2026, 10, 2), Direction.PAYABLE)),
            payments = listOf(payment("p1", "d1", "c1", 1_000, at(2026, 10, 3))),
            receivable = 0,
        )

    val state = homeUiState(view, october, zone)

    assertEquals(Cents.ZERO, state.monthPayments)
    assertEquals(1, state.recent.count { it.kind == MovementKind.PAYMENT })
    assertTrue(state.recent.all { it.direction == Direction.PAYABLE })
  }

  @Test
  fun ratiosSplitTheMonthBetweenDebtsAndPayments() {
    val view =
        ledger(
            debts = listOf(debt("d1", "c1", 3_000, at(2026, 10, 2))),
            payments = listOf(payment("p1", "d1", "c1", 1_000, at(2026, 10, 3))),
            receivable = 3_000,
        )

    val state = homeUiState(view, october, zone)

    assertEquals(0.75f, state.debtsRatio, 0.0001f)
    assertEquals(0.25f, state.paymentsRatio, 0.0001f)
  }

  @Test
  fun noMovementsGivesZeroRatios() {
    val state = homeUiState(ledger(), october, zone)

    assertEquals(0f, state.debtsRatio, 0f)
    assertEquals(0f, state.paymentsRatio, 0f)
  }

  @Test
  fun recentIsNewestFirstAndCappedAtFifty() {
    val debts =
        (0 until 60).map { index ->
          debt("d$index", "c$index", 100L + index, at(2026, 10, 2).plusSeconds(index.toLong()))
        }

    val state = homeUiState(ledger(debts = debts), october, zone)

    assertEquals(50, state.recent.size)
    assertEquals(100L + 59, state.recent.first().amount.value)
    assertEquals(100L + 10, state.recent.last().amount.value)
    assertEquals(
        state.recent.map { it.createdAt }.sortedDescending(),
        state.recent.map { it.createdAt },
    )
  }

  @Test
  fun recentCarriesTheContactNameAndPendingMark() {
    val view =
        ledger(
            contacts = listOf(contact("c1", "سامي")),
            debts = listOf(debt("d1", "c1", 2_500, at(2026, 10, 2), pending = true)),
        )

    val movement = homeUiState(view, october, zone).recent.single()

    assertEquals("سامي", movement.contactName)
    assertEquals("c1", movement.contactId)
    assertEquals(Cents(2_500), movement.amount)
    assertEquals(MovementKind.DEBT, movement.kind)
    assertTrue(movement.pending)
  }

  private fun instantAt(
      year: Int,
      month: Int,
      day: Int,
      hour: Int,
      minute: Int,
  ): Instant = LocalDate.of(year, month, day).atTime(hour, minute).atZone(zone).toInstant()

  private fun at(year: Int, month: Int, day: Int): Instant = instantAt(year, month, day, 12, 0)

  private fun contact(id: String, name: String): ContactView =
      ContactView(
          id = id,
          name = name,
          phone = "+970599000000",
          category = "",
          note = "",
          version = 1,
          pending = false,
          createdAt = at(2026, 9, 1),
          createdBy = "",
          operationId = "op-$id",
      )

  private fun debt(
      id: String,
      contactId: String,
      amount: Long,
      createdAt: Instant,
      direction: Direction = Direction.RECEIVABLE,
      pending: Boolean = false,
  ): DebtView =
      DebtView(
          id = id,
          contactId = contactId,
          direction = direction,
          amount = Cents(amount),
          paid = Cents.ZERO,
          confirmedRemaining = Cents(amount),
          remaining = Cents(amount),
          version = 1,
          pending = pending,
          hasPendingPayments = false,
          note = "",
          dueDate = null,
          createdAt = createdAt,
          createdBy = "",
          operationId = "op-$id",
      )

  private fun payment(
      id: String,
      debtId: String,
      contactId: String,
      amount: Long,
      createdAt: Instant,
      reversed: Boolean = false,
  ): PaymentView =
      PaymentView(
          id = id,
          debtId = debtId,
          contactId = contactId,
          amount = Cents(amount),
          method = PaymentMethod.CASH,
          note = "",
          reversed = reversed,
          pending = false,
          createdAt = createdAt,
          createdBy = "",
          operationId = "op-$id",
      )

  private fun ledger(
      contacts: List<ContactView> = listOf(contact("c1", "سامي")),
      debts: List<DebtView> = emptyList(),
      payments: List<PaymentView> = emptyList(),
      receivable: Long = 0,
  ): LedgerView =
      LedgerView(
          contacts = contacts,
          archivedContacts = emptyList(),
          debts = debts,
          payments = payments,
          receivable = Cents(receivable),
          payable = Cents.ZERO,
          confirmedReceivable = Cents(receivable),
          confirmedPayable = Cents.ZERO,
          pendingContacts = 0,
          pendingDebts = 0,
          pendingPayments = 0,
          excludedOperations = emptySet(),
      )
}
