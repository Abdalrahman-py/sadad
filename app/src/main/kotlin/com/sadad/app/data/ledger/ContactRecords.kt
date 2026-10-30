package com.sadad.app.data.ledger

import com.sadad.ledger.ContactCreate
import com.sadad.ledger.ContactView
import com.sadad.ledger.DebtCreate
import com.sadad.ledger.LedgerView

/** Saved inputs remain in the encrypted outbox when the server refuses them. */
data class FailedContactDraft(
    val contact: ContactView,
    val code: String,
    val debts: List<DebtCreate>,
)

/** The financial ledger stays authoritative; failed inputs are a separate directory overlay. */
data class ContactRecords(val ledger: LedgerView, val failed: List<FailedContactDraft>) {
  val directory: LedgerView
    get() = ledger.copy(contacts = ledger.contacts + failed.map { it.contact })
}

internal fun contactRecords(view: LedgerView, rows: List<OutboxRow>): ContactRecords {
  val known = (view.contacts + view.archivedContacts).mapTo(HashSet()) { it.id }
  val debts =
      rows
          .filter { it.state != OutboxState.CANCELLED }
          .mapNotNull { it.command.payload() as? DebtCreate }
          .groupBy { it.contactId }
  val failed = rows.mapNotNull { row ->
    if (row.state != OutboxState.REJECTED && row.state != OutboxState.UNSENDABLE)
        return@mapNotNull null
    if (row.entityId in known) return@mapNotNull null
    val payload = row.command.payload() as? ContactCreate ?: return@mapNotNull null
    FailedContactDraft(
        contact =
            ContactView(
                id = row.entityId,
                name = payload.name,
                phone = payload.phone.orEmpty(),
                kind = payload.kind,
                category = payload.category.orEmpty(),
                note = payload.note.orEmpty(),
                version = 0,
                pending = false,
                createdAt = row.createdAt,
                createdBy = "",
                operationId = row.operationId,
            ),
        code = row.code ?: "temporary_service_failure",
        debts = debts[row.entityId].orEmpty(),
    )
  }
  return ContactRecords(view, failed)
}
