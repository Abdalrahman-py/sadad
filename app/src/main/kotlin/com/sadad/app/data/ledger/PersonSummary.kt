package com.sadad.app.data.ledger

import com.sadad.app.data.db.ContactSummaryEntity
import com.sadad.app.feature.quickadd.PersonSearch
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentAllocation
import java.time.LocalDate

/**
 * The stored summary for one person, rebuilt from their view: Home, People and quick add read this
 * instead of recomputing. Null when they are not in `view.contacts` (archived, deleted, or
 * excluded), so the caller deletes the row.
 */
fun summaryOf(view: LedgerView, contactId: String): ContactSummaryEntity? {
  val person = view.contacts.firstOrNull { it.id == contactId } ?: return null
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
      (debts.map { it.createdAt } +
              view.payments.filter { it.contactId == contactId }.map { it.createdAt })
          .maxOrNull()
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
