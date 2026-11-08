package com.sadad.ledger

import java.time.DateTimeException

/** The rows one `changes` page names: what [LedgerState.apply] may read for that page. */
data class WorkingSetIds(
    val contacts: Set<String> = emptySet(),
    val debts: Set<String> = emptySet(),
    val payments: Set<String> = emptySet(),
    val offsets: Set<String> = emptySet(),
    /** Contacts of a `contact.delete` that need all their rows loaded. */
    val withAllRows: Set<String> = emptySet(),
)

/**
 * The working set of a page, decoded the way `Fold.accept` decodes: the `entityId` of every
 * accepted event (in its table) and every id its payload names. Rejected events add nothing.
 */
fun Changes.workingSetIds(): WorkingSetIds {
  val contacts = linkedSetOf<String>()
  val debts = linkedSetOf<String>()
  val payments = linkedSetOf<String>()
  val offsets = linkedSetOf<String>()
  val withAllRows = linkedSetOf<String>()
  for (event in events) {
    val rejected =
        event.kind == ChangeEvent.REJECTED_KIND || event.result.status == ReceiptStatus.REJECTED
    if (rejected) continue
    val command: LedgerCommand
    val payload: CommandPayload
    try {
      command = LedgerJson.decodeFromJsonElement(LedgerCommand.serializer(), event.command)
      ServerTime.parse(command.createdAt)
      payload = command.payload()
    } catch (e: IllegalArgumentException) {
      throw IllegalStateException("event ${event.sequence}: unreadable command", e)
    } catch (e: DateTimeException) {
      throw IllegalStateException("event ${event.sequence}: unreadable command", e)
    }
    check(event.kind == command.type.wireName) {
      "event ${event.sequence} is ${event.kind} but carries ${command.type.wireName}"
    }
    when (command.type) {
      CommandType.CONTACT_CREATE,
      CommandType.CONTACT_UPDATE,
      CommandType.CONTACT_ARCHIVE,
      CommandType.CONTACT_RESTORE,
      CommandType.CONTACT_DELETE -> contacts += command.entityId
      CommandType.DEBT_CREATE,
      CommandType.DEBT_CORRECT -> debts += command.entityId
      CommandType.DEBT_OFFSET -> offsets += command.entityId
      CommandType.PAYMENT_CREATE,
      CommandType.PAYMENT_REVERSE -> payments += command.entityId
    }
    when (payload) {
      is ContactCreate -> Unit
      is ContactUpdate -> contacts += payload.contactId
      is ContactArchive -> contacts += payload.contactId
      is ContactRestore -> contacts += payload.contactId
      is ContactDelete -> {
        contacts += payload.contactId
        withAllRows += payload.contactId
      }
      is DebtCreate -> contacts += payload.contactId
      is DebtCorrect -> debts += payload.debtId
      is DebtOffset -> {
        debts += payload.receivableDebtId
        debts += payload.payableDebtId
      }
      is PaymentCreate -> debts += payload.debtId
      is PaymentReverse -> payments += payload.paymentId
    }
  }
  return WorkingSetIds(contacts, debts, payments, offsets, withAllRows)
}
