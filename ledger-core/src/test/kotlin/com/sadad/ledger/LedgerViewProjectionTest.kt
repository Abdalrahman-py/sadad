package com.sadad.ledger

import com.sadad.ledger.Events.at
import com.sadad.ledger.Events.origin
import com.sadad.ledger.Events.otherOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The projection is split into a confirmed half (memoised per state) and a queued half (per
 * outbox). These pin that the split changes nothing: [referenceView] is the single-pass projection
 * as it was before the split, kept here as the oracle.
 */
class LedgerViewProjectionTest {
  private val self = Fixtures.INSTALLATION_ID
  private val staff = "أحمد"

  private fun create(minute: Int, name: String, kind: ContactKind, from: Origin = origin) =
      Commands.contactCreate(from, at(minute), name, kind = kind)

  private val customer = create(0, "سامي", ContactKind.CUSTOMER)
  private val supplier = create(1, "شركة النور", ContactKind.SUPPLIER, otherOrigin)
  private val archivedCustomer = create(2, "مؤرشف", ContactKind.CUSTOMER)
  private val deletedCustomer = create(3, "محذوف", ContactKind.CUSTOMER, otherOrigin)
  private val restoredCustomer = create(4, "عائد", ContactKind.CUSTOMER)

  private fun debt(
      minute: Int,
      of: LedgerCommand,
      direction: Direction,
      cents: Long,
      from: Origin = origin,
      debtOrigin: DebtOrigin? = null,
  ) =
      Commands.debtCreate(
          from,
          at(minute),
          of.entityId,
          direction,
          Cents(cents),
          dueDate = "2026-11-01",
          debtOrigin = debtOrigin,
      )

  private fun pay(minute: Int, on: LedgerCommand, cents: Long, from: Origin = origin) =
      Commands.paymentCreate(from, at(minute), on.entityId, Cents(cents), PaymentMethod.CASH)

  private val owed = debt(10, customer, Direction.RECEIVABLE, 25_000)
  private val owedAgain = debt(11, customer, Direction.RECEIVABLE, 10_000, otherOrigin)
  private val credit =
      debt(12, customer, Direction.PAYABLE, 15_000, debtOrigin = DebtOrigin.OVERPAYMENT)
  private val supplierDebt = debt(13, supplier, Direction.PAYABLE, 124_000, otherOrigin)
  private val archivedDebt = debt(14, archivedCustomer, Direction.RECEIVABLE, 5_000)
  private val deletedDebt = debt(15, deletedCustomer, Direction.RECEIVABLE, 7_000)
  private val restoredDebt = debt(16, restoredCustomer, Direction.RECEIVABLE, 9_000)
  private val partial = pay(20, owed, 5_000)
  private val reversed = pay(21, owed, 3_000, otherOrigin)
  private val supplierPayment = pay(22, supplierDebt, 24_000, otherOrigin)
  private val archivedPayment = pay(23, archivedDebt, 1_000)
  private val deletedPayment = pay(24, deletedDebt, 2_000)
  private val offset =
      Commands.debtOffset(origin, at(30), owedAgain.entityId, credit.entityId, Cents(4_000))

  /**
   * Debts both ways, payments (one reversed), credit, an offset, and archived, deleted and restored
   * Contacts.
   */
  private val state =
      Events.stateOf(
          customer,
          supplier,
          archivedCustomer,
          deletedCustomer,
          restoredCustomer,
          owed,
          owedAgain,
          credit,
          supplierDebt,
          archivedDebt,
          deletedDebt,
          restoredDebt,
          partial,
          reversed,
          supplierPayment,
          archivedPayment,
          deletedPayment,
          offset,
          Commands.paymentReverse(otherOrigin, at(31), reversed.entityId, "خطأ"),
          Commands.debtCorrect(origin, at(32), supplierDebt.entityId, 1, Cents(130_000), "تصحيح"),
          Commands.contactUpdate(origin, at(33), customer.entityId, 1, "سامي أبو أحمد"),
          Commands.contactArchive(origin, at(34), archivedCustomer.entityId, 1, "انتقل"),
          Commands.contactArchive(origin, at(35), deletedCustomer.entityId, 1, "انتقل"),
          Commands.contactDelete(otherOrigin, at(36), deletedCustomer.entityId, 2, "حذف"),
          Commands.contactArchive(origin, at(37), restoredCustomer.entityId, 1, "انتقل"),
          Commands.contactRestore(origin, at(38), restoredCustomer.entityId, 2, "عاد"),
      )

  private val newContact = create(40, "جديد", ContactKind.CUSTOMER)
  private val newDebt = debt(41, newContact, Direction.RECEIVABLE, 8_000)
  private val queuedOnPending =
      Commands.paymentCreate(
          origin,
          at(42),
          newDebt.entityId,
          Cents(3_000),
          PaymentMethod.CASH,
          dependsOn = listOf(newDebt.operationId),
      )
  private val queuedOnConfirmed = pay(43, owed, 15_000)
  private val queuedTooBig = pay(44, owed, 1_000_000)
  private val queuedOffset =
      Commands.debtOffset(origin, at(45), owed.entityId, credit.entityId, Cents(1_000))
  private val queuedUnderArchived = debt(46, archivedCustomer, Direction.RECEIVABLE, 100)
  private val queuedFailedParent = create(47, "مرفوض", ContactKind.CUSTOMER)
  private val queuedFailedChild =
      Commands.debtCreate(
          origin,
          at(48),
          queuedFailedParent.entityId,
          Direction.RECEIVABLE,
          Cents(100),
          dependsOn = listOf(queuedFailedParent.operationId),
      )

  private val queue =
      listOf(
          newContact,
          newDebt,
          queuedOnPending,
          queuedOnConfirmed,
          queuedTooBig,
          queuedOffset,
          queuedUnderArchived,
          queuedFailedChild,
          // Already applied by the server: skipped, not projected twice.
          customer,
          partial,
      )
  private val failed = setOf(queuedFailedParent.operationId)

  @Test
  fun theLedgerIsNonTrivialEnoughToMeanSomething() {
    val view = state.view(queue, failed, self, staff)
    assertEquals(
        listOf("سامي أبو أحمد", "شركة النور", "عائد"),
        view.contacts.map { it.name }.take(3),
    )
    assertEquals(listOf("مؤرشف"), view.archivedContacts.map { it.name })
    assertEquals(false, view.contacts.any { it.name == "محذوف" })
    assertEquals(
        setOf(
            queuedTooBig.operationId,
            queuedUnderArchived.operationId,
            queuedFailedChild.operationId,
        ),
        view.excludedOperations,
    )
    assertNotEquals(Cents.ZERO, view.receivable)
    assertNotEquals(Cents.ZERO, view.payable)
    assertNotEquals(view.receivable, view.confirmedReceivable)
  }

  @Test
  fun viewEqualsTheSinglePassProjectionForEveryQueue() {
    for (pending in listOf(emptyList(), queue, queue.take(3), queue.drop(3), queue.reversed())) {
      for (name in listOf(staff, "")) {
        assertEquals(
            state.referenceView(pending, failed, self, name),
            state.view(pending, failed, self, name),
        )
      }
    }
    assertEquals(
        LedgerState.EMPTY.referenceView(queue, failed, self, staff),
        LedgerState.EMPTY.view(queue, failed, self, staff),
    )
  }

  @Test
  fun oneConfirmedHalfServesEveryQueueWithoutBeingChangedByThem() {
    val confirmed = state.confirmed(self, staff)
    val bare = confirmed.view(emptyList(), emptySet())
    val first = confirmed.view(queue, failed)
    assertEquals(state.referenceView(queue, failed, self, staff), first)
    assertEquals(
        state.referenceView(queue.take(2), emptySet(), self, staff),
        confirmed.view(queue.take(2), emptySet()),
    )
    // Projecting queued commands left the memoised half as it was.
    assertEquals(bare, confirmed.view(emptyList(), emptySet()))
    assertEquals(state.referenceView(emptyList(), emptySet(), self, staff), bare)
  }

  @Test
  fun aQueuedCommandDoesNotRebuildTheConfirmedRows() {
    val confirmed = state.confirmed(self, staff)
    val before = confirmed.view(emptyList(), emptySet())
    val after = confirmed.view(listOf(newContact, newDebt, queuedOnConfirmed), emptySet())
    // Same objects, not equal copies: nothing confirmed was rebuilt to show the entry.
    assertSame(before.contacts.first(), after.contacts.first())
    assertSame(before.payments.first(), after.payments.first())
    assertSame(before.archivedContacts.single(), after.archivedContacts.single())
    val untouched = before.debts.first { it.id == supplierDebt.entityId }
    assertSame(untouched, after.debts.first { it.id == supplierDebt.entityId })
  }
}

/** The projection before the confirmed half was split out; every line is as it was. */
private fun LedgerState.referenceView(
    pending: List<LedgerCommand>,
    failed: Set<String>,
    selfInstallationId: String,
    staffName: String,
): LedgerView {
  fun author(origin: String) = if (origin == selfInstallationId) staffName else ""

  val contactViews = LinkedHashMap<String, ContactView>()
  val archived = mutableListOf<ContactView>()
  for (row in contacts.values) {
    val view =
        ContactView(
            row.id,
            row.name,
            row.phone,
            row.kind,
            row.category,
            row.note,
            row.version,
            pending = false,
            row.createdAt,
            author(row.originInstallationId),
            row.operationId,
        )
    if (row.archived) archived += view else contactViews[row.id] = view
  }

  val paid = HashMap<String, Cents>()
  for (row in payments.values) {
    if (!row.reversed) paid[row.debtId] = (paid[row.debtId] ?: Cents.ZERO) + row.amount
  }
  val offsetted = HashMap<String, Cents>()
  for (row in offsets.values) {
    offsetted[row.receivableDebtId] = (offsetted[row.receivableDebtId] ?: Cents.ZERO) + row.amount
    offsetted[row.payableDebtId] = (offsetted[row.payableDebtId] ?: Cents.ZERO) + row.amount
  }
  val debtViews = LinkedHashMap<String, DebtView>()
  for (row in debts.values) {
    if (row.contactId !in contactViews) continue
    val debtPaid = paid[row.id] ?: Cents.ZERO
    val debtOffsetted = offsetted[row.id] ?: Cents.ZERO
    val open = row.amount - debtPaid - debtOffsetted
    debtViews[row.id] =
        DebtView(
            row.id,
            row.contactId,
            row.direction,
            row.amount,
            debtPaid,
            open,
            open,
            row.version,
            pending = false,
            hasPendingPayments = false,
            row.note,
            row.dueDate,
            row.createdAt,
            author(row.originInstallationId),
            row.operationId,
            origin = row.origin,
            offsetted = debtOffsetted,
        )
  }
  val paymentViews = mutableListOf<PaymentView>()
  for (row in payments.values) {
    val debt = debtViews[row.debtId] ?: continue
    paymentViews +=
        PaymentView(
            row.id,
            row.debtId,
            debt.contactId,
            row.amount,
            row.method,
            row.note,
            row.reversed,
            pending = false,
            row.createdAt,
            author(row.originInstallationId),
            row.operationId,
        )
  }

  val excluded = LinkedHashSet<String>()
  var pendingContacts = 0
  var pendingDebts = 0
  var pendingPayments = 0
  for (command in pending) {
    if (!command.type.offline) continue
    val id = command.entityId
    if (id in contacts || id in debts || id in payments || id in offsets) continue
    if (command.dependsOn.any { it in failed || it in excluded }) {
      excluded += command.operationId
      continue
    }
    val at = ServerTime.parse(command.createdAt)
    val by = author(command.originInstallationId)
    when (val payload = command.payload()) {
      is ContactCreate -> {
        contactViews[id] =
            ContactView(
                id,
                payload.name,
                payload.phone.orEmpty(),
                payload.kind,
                payload.category.orEmpty(),
                payload.note.orEmpty(),
                version = 1,
                pending = true,
                at,
                by,
                command.operationId,
            )
        pendingContacts++
      }
      is DebtCreate -> {
        if (payload.contactId !in contactViews) {
          excluded += command.operationId
          continue
        }
        debtViews[id] =
            DebtView(
                id,
                payload.contactId,
                payload.direction,
                payload.amountCents,
                paid = Cents.ZERO,
                confirmedRemaining = Cents.ZERO,
                remaining = payload.amountCents,
                version = 1,
                pending = true,
                hasPendingPayments = false,
                payload.note.orEmpty(),
                payload.dueDate,
                at,
                by,
                command.operationId,
                origin = payload.origin ?: DebtOrigin.MANUAL,
            )
        pendingDebts++
      }
      is DebtOffset -> {
        val receivable = debtViews[payload.receivableDebtId]
        val payable = debtViews[payload.payableDebtId]
        if (
            receivable == null ||
                payable == null ||
                receivable.contactId != payable.contactId ||
                receivable.direction != Direction.RECEIVABLE ||
                payable.direction != Direction.PAYABLE ||
                payload.amountCents > receivable.remaining ||
                payload.amountCents > payable.remaining
        ) {
          excluded += command.operationId
          continue
        }
        // An offset settles both sides but moves no cash, so no payment row appears.
        debtViews[receivable.id] =
            receivable.copy(remaining = receivable.remaining - payload.amountCents)
        debtViews[payable.id] = payable.copy(remaining = payable.remaining - payload.amountCents)
      }
      is PaymentCreate -> {
        val debt = debtViews[payload.debtId]
        if (debt == null || payload.amountCents > debt.remaining) {
          excluded += command.operationId
          continue
        }
        debtViews[debt.id] =
            debt.copy(remaining = debt.remaining - payload.amountCents, hasPendingPayments = true)
        paymentViews +=
            PaymentView(
                id,
                debt.id,
                debt.contactId,
                payload.amountCents,
                payload.method,
                payload.note.orEmpty(),
                reversed = false,
                pending = true,
                at,
                by,
                command.operationId,
            )
        pendingPayments++
      }
      else -> Unit
    }
  }

  var receivable = Cents.ZERO
  var payable = Cents.ZERO
  var confirmedReceivable = Cents.ZERO
  var confirmedPayable = Cents.ZERO
  for (debt in debtViews.values) {
    when (debt.direction) {
      Direction.RECEIVABLE -> {
        receivable += debt.remaining
        confirmedReceivable += debt.confirmedRemaining
      }
      Direction.PAYABLE -> {
        payable += debt.remaining
        confirmedPayable += debt.confirmedRemaining
      }
    }
  }

  return LedgerView(
      contacts = contactViews.values.toList(),
      archivedContacts = archived,
      debts = debtViews.values.toList(),
      payments = paymentViews,
      receivable = receivable,
      payable = payable,
      confirmedReceivable = confirmedReceivable,
      confirmedPayable = confirmedPayable,
      pendingContacts = pendingContacts,
      pendingDebts = pendingDebts,
      pendingPayments = pendingPayments,
      excludedOperations = excluded,
  )
}
