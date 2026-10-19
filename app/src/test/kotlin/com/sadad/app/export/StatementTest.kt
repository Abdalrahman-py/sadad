package com.sadad.app.export

import com.sadad.app.data.db.Events
import com.sadad.ledger.Cents
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerState
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.view
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatementTest {
  private val september = Instant.parse("2026-09-10T09:00:00Z")
  private val from = Instant.parse("2026-10-01T00:00:00Z")
  private val to = Instant.parse("2026-10-31T23:59:59Z")
  private val contact = Commands.contactCreate(Events.origin, september, "سامي")
  private val oldDebt = debt(september, 10000)
  private val newDebt = debt(from, 5000)
  private val payment =
      Commands.paymentCreate(
          Events.origin,
          from.plusSeconds(1),
          oldDebt.entityId,
          Cents(3000),
          PaymentMethod.CASH,
      )

  private fun debt(at: Instant, cents: Long, direction: Direction = Direction.RECEIVABLE) =
      Commands.debtCreate(
          Events.origin,
          at,
          contact.entityId,
          direction,
          Cents(cents),
          "بضاعة",
          "2026-11-01",
      )

  @Test
  fun octoberCarriesOpeningBalanceAndEveryMovement() {
    val statement =
        Statement.build(exportView(contact, oldDebt, newDebt, payment), contact.entityId, from, to)
            .single()
    assertEquals(Cents(10000), statement.opening)
    assertEquals(listOf(Cents(5000), Cents.ZERO), statement.rows.map { it.debt })
    assertEquals(listOf(Cents.ZERO, Cents(3000)), statement.rows.map { it.payment })
    assertEquals(listOf(Cents(15000), Cents(12000)), statement.rows.map { it.balance })
    assertEquals(listOf("2026-11-01", null), statement.rows.map { it.dueDate })
    assertEquals(Cents(5000), statement.totalDebt)
    assertEquals(Cents(3000), statement.totalPaid)
    assertEquals(Cents(12000), statement.closing)
  }

  @Test
  fun reversedPaymentsNeverAffectRowsOrOpening() {
    val reverse = Commands.paymentReverse(Events.origin, to, payment.entityId, "خطأ")
    val view = exportView(contact, oldDebt, newDebt, payment, reverse)
    assertEquals(Cents(15000), Statement.build(view, null, from, to).single().closing)
    assertEquals(1, Statement.build(view, null, from, to).single().rows.size)
    assertEquals(Cents(15000), Statement.build(view, null, to, null).single().opening)
  }

  @Test
  fun allContactsMeansVisibleContactsIncludingThoseWithoutMovements() {
    val other = Commands.contactCreate(Events.origin, september, "سامي")
    val archived = Commands.contactCreate(Events.origin, september, "قديم")
    val archive = Commands.contactArchive(Events.origin, from, archived.entityId, 1, "أرشفة")
    val statements = Statement.build(exportView(contact, other, archived, archive), null, from, to)
    assertEquals(listOf(contact.entityId, other.entityId), statements.map { it.contact.id })
    assertTrue(statements.all { it.rows.isEmpty() && it.closing == Cents.ZERO })
  }

  @Test
  fun emptyPeriodKeepsOpeningAndIncludesItsPayment() {
    val before = payment.copy(createdAt = "2026-09-20T00:00:00Z")
    val statement = Statement.build(exportView(contact, oldDebt, before), null, from, to).single()
    assertTrue(statement.rows.isEmpty())
    assertEquals(Cents(7000), statement.opening)
    assertEquals(statement.opening, statement.closing)
  }

  @Test
  fun inclusiveBoundariesAndIdOrderAreIndependentOfInsertionOrder() {
    val first = newDebt.copy(entityId = "00000000-0000-4000-8000-000000000001")
    val second = newDebt.copy(entityId = "00000000-0000-4000-8000-000000000002")
    val last = debt(to, 200)
    val outside = debt(to.plusNanos(1), 900).copy(createdAt = to.plusNanos(1).toString())
    val statement =
        Statement.build(exportView(contact, second, first, last, outside), null, from, to).single()
    assertEquals(
        listOf(first.entityId, second.entityId, last.entityId),
        statement.rows.map { it.id },
    )
    assertEquals(Cents(10200), statement.closing)
  }

  @Test
  fun excludesPayableDebtsAndTheirPayments() {
    val payable = debt(september, 9000, Direction.PAYABLE)
    val paid =
        Commands.paymentCreate(
            Events.origin,
            from,
            payable.entityId,
            Cents(4000),
            PaymentMethod.CASH,
        )
    val statement =
        Statement.build(exportView(contact, oldDebt, payable, paid), null, null, null).single()
    assertEquals(1, statement.rows.size)
    assertEquals(Cents(10000), statement.closing)
  }

  @Test
  fun includesQueuedMovementsFromTheViewOnce() {
    val view =
        LedgerState.EMPTY.view(
            listOf(contact, oldDebt, payment),
            emptySet(),
            Events.INSTALLATION_ID,
            "أحمد",
        )
    val statement = Statement.build(view, null, null, null).single()
    assertEquals(Cents(7000), statement.closing)
    assertEquals(2, statement.rows.size)
  }

  @Test(expected = IllegalArgumentException::class)
  fun refusesInvertedPeriod() {
    Statement.build(exportView(contact), null, to, from)
  }

  @Test
  fun unknownContactHasNoStatement() {
    assertTrue(Statement.build(exportView(contact), "missing", null, null).isEmpty())
  }
}

internal fun exportView(vararg commands: LedgerCommand): LedgerView =
    LedgerState.EMPTY.apply(
            Events.page(commands.mapIndexed { i, command -> Events.accepted(command, i + 1L) })
        )
        .view(emptyList(), emptySet(), Events.INSTALLATION_ID, "أحمد")
