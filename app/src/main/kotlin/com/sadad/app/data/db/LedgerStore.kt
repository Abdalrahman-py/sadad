package com.sadad.app.data.db

import androidx.room.withTransaction
import com.sadad.ledger.Changes
import com.sadad.ledger.LedgerState
import com.sadad.ledger.ReceiptStatus
import com.sadad.ledger.WorkingSetIds
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
    private val offsets: OffsetDao = db.offsetDao(),
    private val meta: LedgerMetaDao = db.metaDao(),
    private val events: EventDao = db.eventDao(),
    private val summaries: ContactSummaryDao = db.contactSummaryDao(),
) {
  /** The stored rows, ordered by `createdSeq`, which is the event order the maps must keep. */
  suspend fun load(): LedgerState =
      // One transaction: a statement reads this while a sync page may be landing.
      db.withTransaction {
        val stored = meta.get() ?: return@withTransaction LedgerState.EMPTY
        LedgerState(
            stored.storeId,
            stored.epoch,
            stored.cursor,
            contacts.all().associateTo(LinkedHashMap()) { it.id to it.toRow() },
            debts.all().associateTo(LinkedHashMap()) { it.id to it.toRow() },
            payments.all().associateTo(LinkedHashMap()) { it.id to it.toRow() },
            offsets.all().associateTo(LinkedHashMap()) { it.id to it.toRow() },
        )
      }

  /**
   * The rows one page names, in `createdSeq` order, under [stored] meta: the contacts, debts,
   * payments and offsets by id, each payment's debt, each debt's contact, and for every contact in
   * [WorkingSetIds.withAllRows] all its debts with their payments and offsets.
   */
  suspend fun loadWorkingSet(ids: WorkingSetIds): LedgerState =
      // One transaction: a sync page that lands between the reads must not tear the working set.
      db.withTransaction {
        val stored = meta.get() ?: return@withTransaction LedgerState.EMPTY
        val contactEntities =
            (ids.contacts + ids.withAllRows)
                .chunked(ID_CHUNK)
                .flatMap { contacts.byIds(it) }
                .toMutableList()
        val debtEntities = ids.debts.chunked(ID_CHUNK).flatMap { debts.byIds(it) }.toMutableList()
        val paymentEntities =
            ids.payments.chunked(ID_CHUNK).flatMap { payments.byIds(it) }.toMutableList()
        val offsetEntities =
            ids.offsets.chunked(ID_CHUNK).flatMap { offsets.byIds(it) }.toMutableList()

        // A payment id already stored names its debt; load that debt.
        val knownDebtIds = debtEntities.mapTo(HashSet()) { it.id }
        val missingDebtIds = paymentEntities.map { it.debtId }.toSet() - knownDebtIds
        if (missingDebtIds.isNotEmpty()) {
          debtEntities += missingDebtIds.chunked(ID_CHUNK).flatMap { debts.byIds(it) }
        }
        // A debt id names its contact; load that contact.
        val knownContactIds = contactEntities.mapTo(HashSet()) { it.id }
        val missingContactIds = debtEntities.map { it.contactId }.toSet() - knownContactIds
        if (missingContactIds.isNotEmpty()) {
          contactEntities += missingContactIds.chunked(ID_CHUNK).flatMap { contacts.byIds(it) }
        }
        if (ids.withAllRows.isNotEmpty()) {
          val knownPayments = paymentEntities.mapTo(HashSet()) { it.id }
          val knownOffsets = offsetEntities.mapTo(HashSet()) { it.id }
          val withAllDebts = mutableListOf<DebtEntity>()
          for (cid in ids.withAllRows) withAllDebts += debts.ofContact(cid)
          val presentDebtIds = debtEntities.mapTo(HashSet()) { it.id }
          for (debt in withAllDebts) {
            if (debt.id !in presentDebtIds) {
              debtEntities += debt
              presentDebtIds += debt.id
            }
          }
          val withAllDebtIds = withAllDebts.map { it.id }.toSet()
          if (withAllDebtIds.isNotEmpty()) {
            for (pay in withAllDebtIds.chunked(ID_CHUNK).flatMap { payments.onDebts(it) }) {
              if (pay.id !in knownPayments) {
                paymentEntities += pay
                knownPayments += pay.id
              }
            }
            for (off in withAllDebtIds.chunked(ID_CHUNK).flatMap { offsets.onDebts(it) }) {
              if (off.id !in knownOffsets) {
                offsetEntities += off
                knownOffsets += off.id
              }
            }
          }
        }

        LedgerState(
            stored.storeId,
            stored.epoch,
            stored.cursor,
            contactEntities
                .sortedBy { it.createdSeq }
                .associateTo(LinkedHashMap()) { it.id to it.toRow() },
            debtEntities
                .sortedBy { it.createdSeq }
                .associateTo(LinkedHashMap()) { it.id to it.toRow() },
            paymentEntities
                .sortedBy { it.createdSeq }
                .associateTo(LinkedHashMap()) { it.id to it.toRow() },
            offsetEntities
                .sortedBy { it.createdSeq }
                .associateTo(LinkedHashMap()) { it.id to it.toRow() },
        )
      }

  /**
   * The stored rows of exactly one person: the Contact row even when archived, their Debts, the
   * Payments on those Debts, the Offsets on either side of them, each map in `createdSeq` order
   * (the rule of [load], for one person). A contact id that is not stored yet (a queued new person)
   * yields the empty state under the same store meta; null means no `ledger_meta` yet.
   */
  suspend fun loadPerson(contactId: String): LedgerState? =
      // One transaction: a sync page that lands between the five reads must not tear the person.
      db.withTransaction {
        val stored = meta.get() ?: return@withTransaction null
        personState(
            stored,
            contacts.byId(contactId),
            debts.ofContact(contactId),
            payments.ofContact(contactId),
            offsets.ofContact(contactId),
        )
      }

  /**
   * [loadPerson] again after each commit that touches the ledger tables: one trigger and one
   * transactional read, so a sync page never surfaces half-applied.
   */
  fun observePerson(contactId: String): Flow<LedgerState?> = changes().map { loadPerson(contactId) }

  /** Emits at once, then after each commit that touches the ledger tables. */
  fun changes(): Flow<Set<String>> =
      db.invalidationTracker.createFlow("contacts", "debts", "payments", "offsets", "ledger_meta")

  /**
   * The rows Activity needs for `[from, to)`, null meaning unbounded: the debts created in it, the
   * debts of the payments created in it and [queuedDebts]; every payment and offset on those debts,
   * so what is left on them stays exact; their contacts and [queuedContacts]. Each map keeps
   * `createdSeq` order. Without `ledger_meta` it is the empty state, as in [load].
   */
  suspend fun loadPeriod(
      from: Instant?,
      to: Instant?,
      queuedDebts: Set<String>,
      queuedContacts: Set<String>,
  ): LedgerState =
      // One transaction: a sync page that lands between the reads must not tear the period.
      db.withTransaction {
        val stored = meta.get() ?: return@withTransaction LedgerState.EMPTY
        val period =
            debts.inPeriod(
                from?.toEpochMilli() ?: Long.MIN_VALUE,
                to?.toEpochMilli() ?: Long.MAX_VALUE,
            )
        val debtRows =
            (period + queuedDebts.chunked(ID_CHUNK).flatMap { debts.byIds(it) })
                .sortedBy { it.createdSeq }
                .associateByTo(LinkedHashMap()) { it.id }
        val debtIds = debtRows.keys.chunked(ID_CHUNK)
        val contactIds = debtRows.values.mapTo(HashSet(queuedContacts)) { it.contactId }
        LedgerState(
            stored.storeId,
            stored.epoch,
            stored.cursor,
            contactIds
                .chunked(ID_CHUNK)
                .flatMap { contacts.byIds(it) }
                .sortedBy { it.createdSeq }
                .associateTo(LinkedHashMap()) { it.id to it.toRow() },
            debtRows.mapValuesTo(LinkedHashMap()) { it.value.toRow() },
            debtIds
                .flatMap { payments.onDebts(it) }
                .sortedBy { it.createdSeq }
                .associateTo(LinkedHashMap()) { it.id to it.toRow() },
            // An offset whose two debts fall in two chunks comes back twice; the map keeps one.
            debtIds
                .flatMap { offsets.onDebts(it) }
                .sortedBy { it.createdSeq }
                .associateTo(LinkedHashMap()) { it.id to it.toRow() },
        )
      }

  /** Only the archived contacts, in `createdSeq` order; what the trash lists. */
  suspend fun loadArchived(): LedgerState {
    val stored = meta.get() ?: return LedgerState.EMPTY
    return LedgerState(
        stored.storeId,
        stored.epoch,
        stored.cursor,
        contacts.archived().associateTo(LinkedHashMap()) { it.id to it.toRow() },
        emptyMap(),
        emptyMap(),
    )
  }

  private fun personState(
      stored: LedgerMetaEntity,
      contact: ContactEntity?,
      debts: List<DebtEntity>,
      payments: List<PaymentEntity>,
      offsets: List<OffsetEntity>,
  ): LedgerState {
    contact
        ?: return LedgerState(
            stored.storeId,
            stored.epoch,
            stored.cursor,
            emptyMap(),
            emptyMap(),
            emptyMap(),
        )
    return LedgerState(
        stored.storeId,
        stored.epoch,
        stored.cursor,
        linkedMapOf(contact.id to contact.toRow()),
        debts.associateTo(LinkedHashMap()) { it.id to it.toRow() },
        payments.associateTo(LinkedHashMap()) { it.id to it.toRow() },
        offsets.associateTo(LinkedHashMap()) { it.id to it.toRow() },
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
      for (ids in (old.offsets.keys - new.offsets.keys).chunked(500)) offsets.delete(ids)
      for (ids in (old.payments.keys - new.payments.keys).chunked(500)) payments.delete(ids)
      for (ids in (old.debts.keys - new.debts.keys).chunked(500)) debts.delete(ids)
      for (ids in (old.contacts.keys - new.contacts.keys).chunked(500)) contacts.delete(ids)
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
      for ((id, row) in new.offsets) {
        if (old.offsets[id] === row) continue
        val seq =
            if (old.offsets[id] == null) created.getValue(id)
            else offsets.createdSeq(id) ?: error("offset $id is not stored")
        offsets.upsert(row.toEntity(seq))
      }
      events.insert(page.events.map { it.toEntity() })
      meta.put(LedgerMetaEntity(storeId = new.storeId, epoch = new.epoch, cursor = new.cursor))
      events.trim(EVENT_KEEP)
    }
  }

  /**
   * [persist] plus the summaries of [touched] people rewritten from Room inside the same
   * transaction, after the rows are written: [summarize] reads each person back from Room (so it
   * sees the page's rows) with the caller's outbox on top.
   */
  suspend fun persistWithSummariesFromRoom(
      old: LedgerState,
      new: LedgerState,
      page: Changes,
      touched: Set<String>,
      summarize: suspend (String) -> ContactSummaryEntity?,
  ) {
    db.withTransaction {
      persist(old, new, page)
      if (touched.isNotEmpty()) {
        val upserts = mutableListOf<ContactSummaryEntity>()
        val deletions = mutableListOf<String>()
        for (id in touched) {
          val summary = summarize(id)
          if (summary != null) upserts += summary else deletions += id
        }
        if (upserts.isNotEmpty()) summaries.upsert(upserts)
        if (deletions.isNotEmpty()) summaries.delete(deletions)
      }
    }
  }

  /** How many summary rows are stored; the fill fires only when this is zero. */
  suspend fun summaryCount(): Int = summaries.count()

  /**
   * The one-time fill after the schema 6 update: [rows] are [summaryOf] for every visible person,
   * in one transaction, so a crash cannot leave a half-filled table.
   */
  suspend fun fillSummaries(rows: List<ContactSummaryEntity>) {
    if (rows.isEmpty()) return
    db.withTransaction { summaries.upsert(rows) }
  }

  /** Empties the Ledger when a page comes from another epoch or store. The outbox stays. */
  suspend fun reset() {
    db.withTransaction {
      contacts.clear()
      debts.clear()
      payments.clear()
      offsets.clear()
      events.clear()
      meta.clear()
      summaries.clear()
    }
  }

  companion object {
    /** ponytail: history shows the latest 200; raise the cap if a screen needs more. */
    const val EVENT_KEEP = 500

    /** Under SQLite's 999 bound variables on older Android; an offset query binds a chunk twice. */
    private const val ID_CHUNK = 400
  }
}
