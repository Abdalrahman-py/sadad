package com.sadad.ledger

import java.time.DateTimeException
import java.time.Instant

data class ContactRow(
    val id: String,
    val name: String,
    val phone: String,
    /** Whether the Contact is a customer the store collects from or a supplier it pays. */
    val kind: ContactKind,
    val category: String,
    val note: String,
    val archived: Boolean,
    /** Starts at 1; each update, archive or restore adds 1. */
    val version: Long,
    val operationId: String,
    val createdAt: Instant,
    val originInstallationId: String,
)

data class DebtRow(
    val id: String,
    val contactId: String,
    val direction: Direction,
    /** The current amount, after corrections. */
    val amount: Cents,
    val note: String,
    val dueDate: String?,
    /** Starts at 1; each correction adds 1. */
    val version: Long,
    val operationId: String,
    val createdAt: Instant,
    val originInstallationId: String,
    val origin: DebtOrigin = DebtOrigin.MANUAL,
)

/** One credit settlement between a Contact's receivable and payable Debt. Not cash. */
data class OffsetRow(
    val id: String,
    val receivableDebtId: String,
    val payableDebtId: String,
    val amount: Cents,
    val operationId: String,
    val createdAt: Instant,
    val originInstallationId: String,
)

data class PaymentRow(
    val id: String,
    val debtId: String,
    val amount: Cents,
    val method: PaymentMethod,
    val note: String,
    /** A reversed Payment stays listed but no longer counts. */
    val reversed: Boolean,
    val operationId: String,
    val createdAt: Instant,
    val originInstallationId: String,
)

/**
 * The store's Ledger as the server has accepted it, folded from `changes` events with the rules of
 * `24_apply_one.sql`. Immutable: [apply] returns a new state. Rows keep event order.
 */
data class LedgerState(
    val storeId: Long?,
    val epoch: String?,
    /** The last sequence folded in; pass as `after`. */
    val cursor: Long,
    val contacts: Map<String, ContactRow>,
    val debts: Map<String, DebtRow>,
    val payments: Map<String, PaymentRow>,
    val offsets: Map<String, OffsetRow> = emptyMap(),
) {
  /**
   * Folds one page. Throws [IllegalStateException], leaving this state as it was, when the page
   * cannot belong to this Ledger: an unknown schema or currency, another store or epoch (start
   * again from [EMPTY] with `after=0`), a sequence that does not move forward, or an accepted event
   * that names a row this state does not hold (events were missed). Rejected events only move the
   * cursor.
   */
  fun apply(page: Changes): LedgerState {
    check(page.schemaVersion == 1) { "unsupported changes schemaVersion ${page.schemaVersion}" }
    check(page.currency == "ILS") { "unsupported currency ${page.currency}" }
    if (storeId != null) {
      check(page.storeId == storeId) { "page is for store ${page.storeId}, not $storeId" }
      check(page.epoch == epoch) { "page is for epoch ${page.epoch}, not $epoch" }
    }
    // One copy per page, so folding many events stays linear.
    val fold =
        Fold(
            LinkedHashMap(contacts),
            LinkedHashMap(debts),
            LinkedHashMap(payments),
            LinkedHashMap(offsets),
        )
    var last = cursor
    for (event in page.events) {
      check(event.sequence > last) { "event ${event.sequence} does not follow $last" }
      last = event.sequence
      val rejected =
          event.kind == ChangeEvent.REJECTED_KIND || event.result.status == ReceiptStatus.REJECTED
      if (!rejected) fold.accept(event)
    }
    check(page.nextCursor >= last) { "nextCursor ${page.nextCursor} is behind event $last" }
    return LedgerState(
        page.storeId,
        page.epoch,
        page.nextCursor,
        fold.contacts,
        fold.debts,
        fold.payments,
        fold.offsets,
    )
  }

  companion object {
    val EMPTY = LedgerState(null, null, 0, emptyMap(), emptyMap(), emptyMap())
  }
}

private class Fold(
    val contacts: MutableMap<String, ContactRow>,
    val debts: MutableMap<String, DebtRow>,
    val payments: MutableMap<String, PaymentRow>,
    val offsets: MutableMap<String, OffsetRow>,
) {
  fun accept(event: ChangeEvent) {
    val command: LedgerCommand
    val at: Instant
    val payload: CommandPayload
    try {
      command = LedgerJson.decodeFromJsonElement(LedgerCommand.serializer(), event.command)
      at = ServerTime.parse(command.createdAt)
      payload = command.payload()
    } catch (e: IllegalArgumentException) {
      // Includes SerializationException; a DateTimeException is not one.
      throw IllegalStateException("event ${event.sequence}: unreadable command", e)
    } catch (e: DateTimeException) {
      throw IllegalStateException("event ${event.sequence}: unreadable command", e)
    }
    check(event.kind == command.type.wireName) {
      "event ${event.sequence} is ${event.kind} but carries ${command.type.wireName}"
    }
    val id = command.entityId
    val origin = command.originInstallationId
    when (payload) {
      is ContactCreate -> {
        fresh(id, contacts, event)
        contacts[id] =
            ContactRow(
                id,
                payload.name,
                payload.phone.orEmpty(),
                payload.kind,
                payload.category.orEmpty(),
                payload.note.orEmpty(),
                archived = false,
                version = 1,
                command.operationId,
                at,
                origin,
            )
      }
      is ContactUpdate -> {
        val row = held(contacts, payload.contactId, "Contact", event)
        contacts[row.id] =
            row.copy(
                name = payload.name,
                phone = payload.phone.orEmpty(),
                kind = payload.kind,
                category = payload.category.orEmpty(),
                note = payload.note.orEmpty(),
                version = row.version + 1,
            )
      }
      is ContactArchive -> archive(payload.contactId, archived = true, event)
      is ContactRestore -> archive(payload.contactId, archived = false, event)
      is DebtCreate -> {
        held(contacts, payload.contactId, "Contact", event)
        fresh(id, debts, event)
        debts[id] =
            DebtRow(
                id,
                payload.contactId,
                payload.direction,
                payload.amountCents,
                payload.note.orEmpty(),
                payload.dueDate,
                version = 1,
                command.operationId,
                at,
                origin,
                origin = payload.origin ?: DebtOrigin.MANUAL,
            )
      }
      is DebtOffset -> {
        held(debts, payload.receivableDebtId, "Debt", event)
        held(debts, payload.payableDebtId, "Debt", event)
        fresh(id, offsets, event)
        offsets[id] =
            OffsetRow(
                id,
                payload.receivableDebtId,
                payload.payableDebtId,
                payload.amountCents,
                command.operationId,
                at,
                origin,
            )
      }
      is DebtCorrect -> {
        val row = held(debts, payload.debtId, "Debt", event)
        debts[row.id] = row.copy(amount = payload.amountCents, version = row.version + 1)
      }
      is PaymentCreate -> {
        held(debts, payload.debtId, "Debt", event)
        fresh(id, payments, event)
        payments[id] =
            PaymentRow(
                id,
                payload.debtId,
                payload.amountCents,
                payload.method,
                payload.note.orEmpty(),
                reversed = false,
                command.operationId,
                at,
                origin,
            )
      }
      is PaymentReverse -> {
        val row = held(payments, payload.paymentId, "Payment", event)
        payments[row.id] = row.copy(reversed = true)
      }
    }
  }

  private fun archive(contactId: String, archived: Boolean, event: ChangeEvent) {
    val row = held(contacts, contactId, "Contact", event)
    contacts[row.id] = row.copy(archived = archived, version = row.version + 1)
  }

  private fun <T> held(rows: Map<String, T>, id: String, what: String, event: ChangeEvent): T =
      rows[id] ?: throw IllegalStateException("event ${event.sequence}: $what $id is unknown")

  private fun fresh(id: String, rows: Map<String, *>, event: ChangeEvent) =
      check(id !in rows) { "event ${event.sequence}: $id already exists" }
}
