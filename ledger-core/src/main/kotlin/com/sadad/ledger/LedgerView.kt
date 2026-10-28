package com.sadad.ledger

import java.time.Instant

data class ContactView(
    val id: String,
    val name: String,
    val phone: String,
    /** Whether the Contact is a customer the store collects from or a supplier it pays. */
    val kind: ContactKind = ContactKind.CUSTOMER,
    val category: String,
    val note: String,
    /** Send as `expectedVersion` with the next update, archive or restore. */
    val version: Long,
    val pending: Boolean,
    val createdAt: Instant,
    /** The staff name when this installation created it; "" otherwise. */
    val createdBy: String,
    val operationId: String,
)

data class DebtView(
    val id: String,
    val contactId: String,
    val direction: Direction,
    val amount: Cents,
    /** Sum of the confirmed, non-reversed Payments. */
    val paid: Cents,
    /** `amount - paid`; zero for a Debt the server has not accepted yet. */
    val confirmedRemaining: Cents,
    /** What is still open once queued Payments land. */
    val remaining: Cents,
    val version: Long,
    val pending: Boolean,
    val hasPendingPayments: Boolean,
    val note: String,
    val dueDate: String?,
    val createdAt: Instant,
    val createdBy: String,
    /** The operation that created it; a Payment on a pending Debt depends on it. */
    val operationId: String,
    val origin: DebtOrigin = DebtOrigin.MANUAL,
    /** Sum of the confirmed credit Offsets on this Debt; never cash, never part of [paid]. */
    val offsetted: Cents = Cents.ZERO,
)

data class PaymentView(
    val id: String,
    val debtId: String,
    val contactId: String,
    val amount: Cents,
    val method: PaymentMethod,
    val note: String,
    val reversed: Boolean,
    val pending: Boolean,
    val createdAt: Instant,
    val createdBy: String,
    val operationId: String,
)

/** What the screens show: the confirmed Ledger with this device's queued commands on top. */
data class LedgerView(
    val contacts: List<ContactView>,
    val archivedContacts: List<ContactView>,
    val debts: List<DebtView>,
    val payments: List<PaymentView>,
    val receivable: Cents,
    val payable: Cents,
    val confirmedReceivable: Cents,
    val confirmedPayable: Cents,
    val pendingContacts: Int,
    val pendingDebts: Int,
    val pendingPayments: Int,
    /** Queued commands that cannot apply as they stand and are not shown. */
    val excludedOperations: Set<String>,
) {
  /**
   * What this Contact is worth: open receivable minus open payable, queued entries included.
   * Positive the Contact owes the store (عليه); negative the store holds their money (له).
   */
  fun net(contactId: String): Cents =
      debts
          .filter { it.contactId == contactId }
          .fold(Cents.ZERO) { sum, debt ->
            when (debt.direction) {
              Direction.RECEIVABLE -> sum + debt.remaining
              Direction.PAYABLE -> sum - debt.remaining
            }
          }
}

/**
 * Projects this state with the outbox's unsettled commands ([pending], in queue order). [failed]
 * holds operation ids the outbox knows were rejected or cancelled. An archived Contact, its Debts
 * and their Payments are left out of every list and total except [LedgerView.archivedContacts].
 *
 * Only offline types are projected. A queued command whose entity the state already holds is
 * skipped (the server applied it). One is excluded when a dependency failed or was excluded, when a
 * Debt's Contact is not visible, or when a Payment's Debt is not visible or the Payment exceeds
 * what is left on it, counting earlier queued Payments.
 */
fun LedgerState.view(
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
