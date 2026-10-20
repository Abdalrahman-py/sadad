package com.sadad.app.feature.reports

import com.sadad.app.data.db.Events
import com.sadad.app.testing.TestGraph
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentMethod
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.first

/** Seeding and the fixed clock the reports tests share: zone `Asia/Gaza`, 17 October 2026. */
internal val OCTOBER: YearMonth = YearMonth.of(2026, 10)

internal val zone = ZoneId.of("Asia/Gaza")
internal val clock: Clock =
    Clock.fixed(LocalDate.of(2026, 10, 17).atTime(12, 0).atZone(zone).toInstant(), zone)

internal fun at(text: String): Instant = Instant.parse(text)

private val sequences = java.util.WeakHashMap<TestGraph, LongArray>()

internal suspend fun TestGraph.view(): LedgerView =
    checkNotNull(checkNotNull(graph.current.value).repository.view.first { it != null })

/** Folds one accepted command and waits until the repository's view, projected apart, shows it. */
internal suspend fun TestGraph.fold(command: LedgerCommand) {
  val repository = checkNotNull(graph.current.value).repository
  val before = repository.view.value
  repository.applyPage(Events.page(listOf(Events.accepted(command, nextSequence()))))
  repository.view.first { it != null && it != before }
}

internal suspend fun TestGraph.confirmedContact(
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

internal suspend fun TestGraph.confirmedDebt(
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

internal suspend fun TestGraph.confirmedPayment(
    debtId: String,
    amount: Long,
    at: Instant,
): String {
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
 * October: Debts 100 ₪ (سامي) and 50 ₪ (ليلى), a Payment of 30 ₪ and a Payment of 10 ₪ that is then
 * reversed. September: a Debt of 70 ₪ (عمر). Open balance 100 + 50 + 70 - 30 = 190 ₪.
 */
internal suspend fun TestGraph.seedOctober() {
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

private fun TestGraph.nextSequence(): Long =
    synchronized(sequences) { sequences.getOrPut(this) { LongArray(1) }.let { ++it[0] } }
