package com.sadad.app.data.ledger

import com.sadad.app.data.db.ContactSummaryEntity
import com.sadad.app.feature.quickadd.PersonSearch
import com.sadad.ledger.Cents
import com.sadad.ledger.ContactArchive
import com.sadad.ledger.ContactCreate
import com.sadad.ledger.ContactDelete
import com.sadad.ledger.ContactRestore
import com.sadad.ledger.ContactUpdate
import com.sadad.ledger.DebtCorrect
import com.sadad.ledger.DebtCreate
import com.sadad.ledger.DebtOffset
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentAllocation
import com.sadad.ledger.PaymentCreate
import com.sadad.ledger.PaymentReverse
import java.time.LocalDate

/**
 * The stored summary for one person, rebuilt from their view: Home, People and quick add read this
 * instead of recomputing. Null when they are not in `view.contacts` (archived, deleted, or
 * excluded), so the caller deletes the row.
 */
fun summaryOf(view: LedgerView, contactId: String): ContactSummaryEntity? {
  val person = view.contact(contactId) ?: return null
  val debts = view.debtsOf(contactId)
  val earliestOpenDue =
      debts
          .filter { it.direction == Direction.RECEIVABLE && it.remaining > Cents.ZERO }
          .mapNotNull {
            it.dueDate?.let { due -> runCatching { LocalDate.parse(due) }.getOrNull() }
          }
          .minOrNull()
          ?.toString()
  val lastEntryAt =
      (debts.map { it.createdAt } + view.paymentsOf(contactId).map { it.createdAt }).maxOrNull()
  return ContactSummaryEntity(
      contactId = contactId,
      name = person.name,
      phone = person.phone,
      kind = person.kind,
      category = person.category,
      pending = person.pending,
      createdAt = person.createdAt,
      net = view.net(contactId).value,
      openReceivable = PaymentAllocation.openBalance(view, contactId, Direction.RECEIVABLE).value,
      openPayable = PaymentAllocation.openBalance(view, contactId, Direction.PAYABLE).value,
      earliestOpenDue = earliestOpenDue,
      lastEntryAt = lastEntryAt,
      searchKey = PersonSearch.normalize(person.name),
  )
}

/**
 * The one person an outbox command names, or null when its debt's owner is unknown. Suspend,
 * because the owner is read from Room by debt id ([debtOwner]) or from a queued `debt.create` in
 * the same outbox snapshot; the caller supplies both lookups. `payment.reverse` goes through the
 * payment's debt ([paymentDebt]).
 */
suspend fun personOf(
    command: LedgerCommand,
    debtOwner: suspend (String) -> String?,
    paymentDebt: suspend (String) -> String? = { null },
): String? =
    when (val payload = command.payload()) {
      is ContactCreate -> command.entityId
      is ContactUpdate -> payload.contactId
      is ContactArchive -> payload.contactId
      is ContactRestore -> payload.contactId
      is ContactDelete -> payload.contactId
      is DebtCreate -> payload.contactId
      is DebtCorrect -> debtOwner(payload.debtId)
      is PaymentCreate -> debtOwner(payload.debtId)
      is PaymentReverse -> paymentDebt(payload.paymentId)?.let { debtOwner(it) }
      is DebtOffset -> debtOwner(payload.receivableDebtId)
    }
