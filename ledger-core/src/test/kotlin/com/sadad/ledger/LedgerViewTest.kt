package com.sadad.ledger

import com.sadad.ledger.Events.at
import com.sadad.ledger.Events.origin
import com.sadad.ledger.Events.otherOrigin
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerViewTest {
  private val self = Fixtures.INSTALLATION_ID
  private val staff = "أحمد"

  private fun LedgerState.viewWith(
      pending: List<LedgerCommand> = emptyList(),
      failed: Set<String> = emptySet(),
  ) = view(pending, failed, self, staff)

  private val contact = Commands.contactCreate(origin, at(0), "سامي")
  private val debt =
      Commands.debtCreate(origin, at(1), contact.entityId, Direction.RECEIVABLE, Cents(10000))

  @Test
  fun projectsAQueuedCommandTimeThatCarriesAnOffset() {
    val shifted = contact.copy(createdAt = "2026-10-16T12:00:00.000+03:00")
    val view = LedgerState.EMPTY.viewWith(listOf(shifted))
    assertEquals(Instant.parse("2026-10-16T09:00:00Z"), view.contacts.single().createdAt)
  }

  @Test
  fun emptyStateHasNothing() {
    val view = LedgerState.EMPTY.viewWith()
    assertTrue(view.contacts.isEmpty() && view.debts.isEmpty() && view.payments.isEmpty())
    assertTrue(view.archivedContacts.isEmpty())
    listOf(view.receivable, view.payable, view.confirmedReceivable, view.confirmedPayable).forEach {
      assertEquals(Cents.ZERO, it)
    }
    assertEquals(0, view.pendingContacts + view.pendingDebts + view.pendingPayments)
    assertTrue(view.excludedOperations.isEmpty())
  }

  @Test
  fun queuedContactShowsAtOnce() {
    val view = LedgerState.EMPTY.viewWith(listOf(contact))
    assertTrue(view.contacts.single().pending)
    assertEquals(1, view.pendingContacts)
  }

  @Test
  fun attributionNamesOnlyThisInstallation() {
    val mine = Commands.contactCreate(origin, at(0), "من هنا")
    val theirs = Commands.contactCreate(otherOrigin, at(1), "من هناك")
    val confirmedMine = Commands.contactCreate(origin, at(2), "مؤكد هنا")
    val confirmedTheirs = Commands.contactCreate(otherOrigin, at(3), "مؤكد هناك")
    val view = Events.stateOf(confirmedMine, confirmedTheirs).viewWith(listOf(mine, theirs))
    val createdBy = view.contacts.associate { it.name to it.createdBy }
    assertEquals(
        mapOf("مؤكد هنا" to staff, "مؤكد هناك" to "", "من هنا" to staff, "من هناك" to ""),
        createdBy,
    )
  }

  @Test
  fun pendingUnderPending() {
    val payment =
        Commands.paymentCreate(
            origin,
            at(2),
            debt.entityId,
            Cents(4000),
            PaymentMethod.CASH,
            dependsOn = listOf(debt.operationId),
        )
    val view = LedgerState.EMPTY.viewWith(listOf(contact, debt, payment))
    val row = view.debts.single()
    assertTrue(row.pending)
    assertEquals(Cents(6000), row.remaining)
    assertEquals(Cents.ZERO, row.confirmedRemaining)
    assertTrue(row.hasPendingPayments)
    assertEquals(Cents(6000), view.receivable)
    assertEquals(Cents.ZERO, view.confirmedReceivable)
    assertEquals(1 to 1, view.pendingDebts to view.pendingPayments)
    assertTrue(view.payments.single().pending)
  }

  @Test
  fun pendingPaymentsAboveRemainingAreExcluded() {
    val state = Events.stateOf(contact, debt)
    val tooLarge =
        Commands.paymentCreate(origin, at(2), debt.entityId, Cents(10001), PaymentMethod.CASH)
    val first =
        Commands.paymentCreate(origin, at(3), debt.entityId, Cents(7000), PaymentMethod.CASH)
    val second =
        Commands.paymentCreate(origin, at(4), debt.entityId, Cents(4000), PaymentMethod.CASH)
    val view = state.viewWith(listOf(tooLarge, first, second))
    assertEquals(setOf(tooLarge.operationId, second.operationId), view.excludedOperations)
    assertEquals(listOf(first.entityId), view.payments.map { it.id })
    assertEquals(Cents(3000), view.debts.single().remaining)
    assertEquals(Cents(10000), view.debts.single().confirmedRemaining)
    assertEquals(Cents(3000), view.receivable)
  }

  @Test
  fun failedDependenciesExcludeTheirDependants() {
    val failedContact = Commands.contactCreate(origin, at(0), "مرفوض")
    val queuedDebt =
        Commands.debtCreate(
            origin,
            at(1),
            failedContact.entityId,
            Direction.RECEIVABLE,
            Cents(500),
            dependsOn = listOf(failedContact.operationId),
        )
    val queuedPayment =
        Commands.paymentCreate(
            origin,
            at(2),
            queuedDebt.entityId,
            Cents(100),
            PaymentMethod.CASH,
            dependsOn = listOf(queuedDebt.operationId),
        )
    val view =
        LedgerState.EMPTY.viewWith(
            listOf(failedContact, queuedDebt, queuedPayment),
            failed = setOf(failedContact.operationId),
        )
    assertEquals(setOf(queuedDebt.operationId, queuedPayment.operationId), view.excludedOperations)
    assertTrue(view.debts.isEmpty())
    assertTrue(view.payments.isEmpty())
  }

  @Test
  fun alreadyAppliedCommandsCountOnce() {
    val state = Events.stateOf(contact, debt)
    val view = state.viewWith(listOf(contact, debt))
    assertTrue(view.excludedOperations.isEmpty())
    assertEquals(1, view.contacts.size)
    assertEquals(1, view.debts.size)
    assertFalse(view.debts.single().pending)
    assertEquals(0, view.pendingContacts + view.pendingDebts)
    assertEquals(Cents(10000), view.receivable)
  }

  @Test
  fun debtUnderArchivedContactIsExcluded() {
    val archive = Commands.contactArchive(origin, at(2), contact.entityId, 1, "انتقل")
    val queued =
        Commands.debtCreate(origin, at(3), contact.entityId, Direction.RECEIVABLE, Cents(10))
    val view = Events.stateOf(contact, archive).viewWith(listOf(queued))
    assertEquals(setOf(queued.operationId), view.excludedOperations)
    assertTrue(view.debts.isEmpty())
  }

  @Test
  fun onlineOnlyCommandsDoNotChangeTheView() {
    val state = Events.stateOf(contact, debt)
    val archive = Commands.contactArchive(origin, at(2), contact.entityId, 1, "انتقل")
    val correct = Commands.debtCorrect(origin, at(3), debt.entityId, 1, Cents(1), "تصحيح")
    assertEquals(state.viewWith(), state.viewWith(listOf(archive, correct)))
  }
}
