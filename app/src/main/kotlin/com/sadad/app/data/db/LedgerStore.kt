package com.sadad.app.data.db

import androidx.room.withTransaction
import com.sadad.ledger.Changes
import com.sadad.ledger.LedgerState
import com.sadad.ledger.ReceiptStatus

/**
 * The Room side of the in-memory [LedgerState]: [load] rebuilds it once when a store opens, and
 * [persist] writes one folded `changes` page in one transaction, so a crash keeps every page that
 * finished.
 */
class LedgerStore(
    private val db: LedgerDatabase,
    // The DAOs are parameters so a test can count the rows one page writes.
    private val contacts: ContactDao = db.contactDao(),
    private val debts: DebtDao = db.debtDao(),
    private val payments: PaymentDao = db.paymentDao(),
    private val meta: LedgerMetaDao = db.metaDao(),
    private val events: EventDao = db.eventDao(),
) {
  /** The stored rows, ordered by `createdSeq`, which is the event order the maps must keep. */
  suspend fun load(): LedgerState {
    val stored = meta.get() ?: return LedgerState.EMPTY
    return LedgerState(
        stored.storeId,
        stored.epoch,
        stored.cursor,
        contacts.all().associateTo(LinkedHashMap()) { it.id to it.toRow() },
        debts.all().associateTo(LinkedHashMap()) { it.id to it.toRow() },
        payments.all().associateTo(LinkedHashMap()) { it.id to it.toRow() },
    )
  }

  /**
   * Writes the rows one page changed (`new[id] !== old[id]`), the page's events, the cursor, then
   * drops all but the newest [EVENT_KEEP] events. One transaction for all of it.
   */
  suspend fun persist(old: LedgerState, new: LedgerState, page: Changes) {
    // A new row takes the sequence of the page event whose entityId is its id. A rejected event
    // created nothing, so it never names a row's sequence.
    val created = HashMap<String, Long>()
    for (event in page.events) {
      if (event.result.status == ReceiptStatus.REJECTED) continue
      created.getOrPut(event.entityId) { event.sequence }
    }
    db.withTransaction {
      for ((id, row) in new.contacts) {
        if (old.contacts[id] === row) continue
        val seq =
            if (old.contacts[id] == null) created.getValue(id)
            else contacts.createdSeq(id) ?: error("contact $id is not stored")
        contacts.upsert(row.toEntity(seq))
      }
      for ((id, row) in new.debts) {
        if (old.debts[id] === row) continue
        val seq =
            if (old.debts[id] == null) created.getValue(id)
            else debts.createdSeq(id) ?: error("debt $id is not stored")
        debts.upsert(row.toEntity(seq))
      }
      for ((id, row) in new.payments) {
        if (old.payments[id] === row) continue
        val seq =
            if (old.payments[id] == null) created.getValue(id)
            else payments.createdSeq(id) ?: error("payment $id is not stored")
        payments.upsert(row.toEntity(seq))
      }
      events.insert(page.events.map { it.toEntity() })
      meta.put(LedgerMetaEntity(storeId = new.storeId, epoch = new.epoch, cursor = new.cursor))
      events.trim(EVENT_KEEP)
    }
  }

  /** Empties the Ledger when a page comes from another epoch or store. The outbox stays. */
  suspend fun reset() {
    db.withTransaction {
      contacts.clear()
      debts.clear()
      payments.clear()
      events.clear()
      meta.clear()
    }
  }

  companion object {
    /** ponytail: history shows the latest 200; raise the cap if a screen needs more. */
    const val EVENT_KEEP = 500
  }
}
