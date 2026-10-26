package com.sadad.ledger

import com.sadad.ledger.Events.at
import com.sadad.ledger.Events.origin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentAllocationTest {
  private val contact = Commands.contactCreate(origin, at(0), "سامي")
  private val older =
      Commands.debtCreate(origin, at(1), contact.entityId, Direction.RECEIVABLE, Cents(10000))
  private val newer =
      Commands.debtCreate(origin, at(2), contact.entityId, Direction.RECEIVABLE, Cents(5000))

  private fun LedgerState.viewWith(pending: List<LedgerCommand> = emptyList()) =
      view(pending, emptySet(), Fixtures.INSTALLATION_ID, "أحمد")

  @Test
  fun spansDebtsOldestFirst() {
    // Queued newest first, so the order comes from createdAt, not from the list.
    val view = Events.stateOf(contact, newer, older).viewWith()
    val parts = PaymentAllocation.allocate(view, contact.entityId, Cents(12000))
    assertEquals(
        listOf(older.entityId to Cents(10000), newer.entityId to Cents(2000)),
        parts.map { it.debtId to it.amount },
    )
    assertEquals(Cents(12000), parts.fold(Cents.ZERO) { sum, part -> sum + part.amount })
    assertEquals(older.operationId, parts.first().debtOperationId)
  }

  @Test
  fun refusesOverpaymentAndNonPositiveAmounts() {
    val view = Events.stateOf(contact, older, newer).viewWith()
    for (amount in listOf(Cents(15001), Cents(0), Cents(-1))) {
      assertThrows(IllegalArgumentException::class.java) {
        PaymentAllocation.allocate(view, contact.entityId, amount)
      }
    }
    assertEquals(2, PaymentAllocation.allocate(view, contact.entityId, Cents(15000)).size)
  }

  @Test
  fun skipsSettledPayableAndOtherContactsDebts() {
    val other = Commands.contactCreate(origin, at(0), "غيره")
    val othersDebt =
        Commands.debtCreate(origin, at(0), other.entityId, Direction.RECEIVABLE, Cents(900))
    val owed = Commands.debtCreate(origin, at(0), contact.entityId, Direction.PAYABLE, Cents(900))
    val settled =
        Commands.debtCreate(origin, at(0), contact.entityId, Direction.RECEIVABLE, Cents(300))
    val paidOff =
        Commands.paymentCreate(origin, at(3), settled.entityId, Cents(300), PaymentMethod.BANK)
    val view = Events.stateOf(contact, other, othersDebt, owed, settled, paidOff, newer).viewWith()
    val parts = PaymentAllocation.allocate(view, contact.entityId, Cents(5000))
    assertEquals(listOf(newer.entityId), parts.map { it.debtId })
    assertThrows(IllegalArgumentException::class.java) {
      PaymentAllocation.allocate(view, contact.entityId, Cents(5001))
    }
  }

  @Test
  fun pendingPaymentsReduceWhatIsLeft() {
    val queued =
        Commands.paymentCreate(origin, at(3), older.entityId, Cents(9000), PaymentMethod.CASH)
    val view = Events.stateOf(contact, older, newer).viewWith(listOf(queued))
    val parts = PaymentAllocation.allocate(view, contact.entityId, Cents(3000))
    assertEquals(listOf(Cents(1000), Cents(2000)), parts.map { it.amount })
    assertThrows(IllegalArgumentException::class.java) {
      PaymentAllocation.allocate(view, contact.entityId, Cents(6001))
    }
  }

  @Test
  fun tiesKeepViewOrder() {
    val first =
        Commands.debtCreate(origin, at(5), contact.entityId, Direction.RECEIVABLE, Cents(100))
    val second =
        Commands.debtCreate(origin, at(5), contact.entityId, Direction.RECEIVABLE, Cents(100))
    val view = Events.stateOf(contact, first, second).viewWith()
    val parts = PaymentAllocation.allocate(view, contact.entityId, Cents(150))
    assertEquals(
        listOf(first.entityId to Cents(100), second.entityId to Cents(50)),
        parts.map { it.debtId to it.amount },
    )
  }

  @Test
  fun partsForAPendingDebtNameItsOperation() {
    val queuedDebt =
        Commands.debtCreate(origin, at(4), contact.entityId, Direction.RECEIVABLE, Cents(400))
    val view = Events.stateOf(contact, older).viewWith(listOf(queuedDebt))
    val parts = PaymentAllocation.allocate(view, contact.entityId, Cents(10400))
    assertEquals(2, parts.size)
    val pendingPart = parts.last()
    assertTrue(pendingPart.debtPending)
    assertEquals(queuedDebt.operationId, pendingPart.debtOperationId)
    assertEquals(Cents(400), pendingPart.amount)
    assertEquals(false, parts.first().debtPending)
  }

  @Test
  fun splitsASupplierAcrossItsOpenPayableDebtsOldestFirst() {
    val supplier = Commands.contactCreate(origin, at(0), "شركة النور", kind = ContactKind.SUPPLIER)
    val older =
        Commands.debtCreate(origin, at(1), supplier.entityId, Direction.PAYABLE, Cents(20_000))
    val newer =
        Commands.debtCreate(origin, at(2), supplier.entityId, Direction.PAYABLE, Cents(50_000))
    val receivable =
        Commands.debtCreate(origin, at(3), supplier.entityId, Direction.RECEIVABLE, Cents(9_000))
    val view = Events.stateOf(supplier, newer, older, receivable).viewWith()

    val parts =
        PaymentAllocation.allocate(view, supplier.entityId, Cents(30_000), Direction.PAYABLE)
    assertEquals(
        listOf(older.entityId to Cents(20_000), newer.entityId to Cents(10_000)),
        parts.map { it.debtId to it.amount },
    )
    assertThrows(IllegalArgumentException::class.java) {
      // The supplier's receivable Debt is not a payable pool, and 80_000 is all the payables.
      PaymentAllocation.allocate(view, supplier.entityId, Cents(70_001), Direction.PAYABLE)
    }
  }
}
