package com.sadad.app.data.ledger

import android.os.Build
import android.os.Trace
import androidx.room.withTransaction
import com.sadad.app.data.db.ContactSummaryDao
import com.sadad.app.data.db.ContactSummaryEntity
import com.sadad.app.data.db.LedgerDatabase
import com.sadad.app.data.db.LedgerStore
import com.sadad.ledger.Cents
import com.sadad.ledger.ChangeEvent
import com.sadad.ledger.Changes
import com.sadad.ledger.CommandType
import com.sadad.ledger.Commands
import com.sadad.ledger.ContactArchive
import com.sadad.ledger.ContactCreate
import com.sadad.ledger.ContactDelete
import com.sadad.ledger.ContactKind
import com.sadad.ledger.ContactRestore
import com.sadad.ledger.ContactUpdate
import com.sadad.ledger.ContactView
import com.sadad.ledger.DebtCorrect
import com.sadad.ledger.DebtCreate
import com.sadad.ledger.DebtOffset
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerJson
import com.sadad.ledger.LedgerState
import com.sadad.ledger.LedgerView
import com.sadad.ledger.Origin
import com.sadad.ledger.PaymentCreate
import com.sadad.ledger.PaymentReverse
import com.sadad.ledger.ReceiptStatus
import com.sadad.ledger.confirmed
import com.sadad.ledger.view
import com.sadad.ledger.workingSetIds
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val traceCookies = AtomicInteger()

/**
 * Marks a span for the Macrobenchmarks (`TraceSectionMetric`). Async, because a coroutine can
 * resume on another thread and `Trace.beginSection` is bound to one; it needs API 29 and is a no-op
 * below that.
 */
internal suspend fun <T> traced(name: String, block: suspend () -> T): T {
  if (Build.VERSION.SDK_INT < 29) return block()
  val cookie = traceCookies.incrementAndGet()
  Trace.beginAsyncSection(name, cookie)
  try {
    return block()
  } finally {
    Trace.endAsyncSection(name, cookie)
  }
}

/** A page from another epoch or store: the state was emptied, so sync must pull from 0 again. */
class LedgerReset(cause: Throwable) : Exception(cause.message, cause)

/** Why a write was refused before anything reached the outbox. */
sealed class Refusal(message: String) : Exception(message) {
  data object NotReady : Refusal("the ledger has no origin yet")

  data object OnlineOnly : Refusal("this action needs a connection")

  data object OfflineNotAllowed : Refusal("offline writes are not allowed")

  data class Invalid(override val message: String) : Refusal(message)

  data object Unknown : Refusal("the row is not in the ledger view")
}

/**
 * The optional Debt a new Contact starts with. It carries no direction: the Contact's kind decides
 * it ([ContactKind.debtDirection]), so a caller cannot write a supplier's balance on the wrong
 * side.
 */
data class FirstDebt(
    val amount: Cents,
    val note: String? = null,
    val dueDate: String? = null,
)

/** What the repository needs from the open store; ticket 46 implements it, the tests fake it. */
interface RepositorySession {
  /** Null until the store is enrolled and the first page gave an epoch. */
  fun origin(): Origin?

  val selfInstallationId: String

  val staffName: Flow<String>

  fun isOnline(): Boolean

  /** A valid offline lease (ticket 47). */
  fun offlineAllowed(): Boolean

  /** Starts a sync pass; ticket 47 wires it to WorkManager. */
  fun onQueued()
}

/**
 * The open store's writes and pages: Room is the only copy of the ledger (ticket 168), so nothing
 * here is resident — the writes check one person's Room rows, and a page persists its rows and
 * summaries in one transaction. Queued commands wait in the [Outbox].
 */
class LedgerRepository(
    private val store: LedgerStore,
    private val outbox: Outbox,
    private val session: RepositorySession,
    private val scope: CoroutineScope,
) {
  private val mutex = Mutex()

  // The summary table is ready when no fill is needed, or when the one-time fill ends.
  private val _summaryReady = MutableStateFlow(false)
  val summaryReady: StateFlow<Boolean> = _summaryReady.asStateFlow()

  /**
   * The one call opening a store makes, and the only whole-store read left (ticket 160's fill): on
   * an updated phone the summary table is empty while the rows are here, so the fill rebuilds it
   * from one [LedgerStore.load] snapshot and drops it — the heap never grows with the store. The
   * fill runs in the background under [mutex], so opening does not wait; a page sent in the
   * meantime waits on that lock. A failed fill writes nothing, releases the lock and rethrows, so
   * the app ends loudly and the next open retries. Every other open answers with one count per
   * table: no full table is read.
   */
  suspend fun load() {
    if (store.summaryCount() > 0 || store.contactCount() == 0) {
      _summaryReady.value = true
      return
    }
    mutex.lock()
    scope.launch(Dispatchers.Default) {
      try {
        // A second load that started while this fill ran passes the check above (count still 0)
        // and refills here unless it re-checks under the lock.
        if (store.summaryCount() > 0) {
          _summaryReady.value = true
          return@launch
        }
        val rows = outbox.snapshot()
        val current = traced("ledger_load") { store.load() }
        val view =
            current
                .confirmed(session.selfInstallationId, "")
                .view(pendingCommands(rows), failedOperations(rows))
        store.fillSummaries(view.contacts.mapNotNull { summaryOf(view, it.id) })
        _summaryReady.value = true
      } finally {
        mutex.unlock()
      }
    }
  }

  /**
   * Follows the outbox: every added, removed or state-changed row rewrites the summary of the one
   * person it names, from that person's [PersonLedger] view, in one Room transaction per emission.
   * The first emission is diffed against an empty list, so opening a store rewrites every person
   * the outbox names. Waits for [summaryReady] first, so a row never lands in a table the fill is
   * still writing, and rewrites under the same [mutex] as [applyPage], so the two never interleave
   * inside one person's rewrite. Launched by `StoreScope`; the tests launch it themselves.
   */
  suspend fun followOutbox(
      database: LedgerDatabase,
      summaries: ContactSummaryDao = database.contactSummaryDao(),
  ) =
      withContext(Dispatchers.Default) {
        summaryReady.first { it }
        val personLedger = PersonLedger(store, outbox, session)
        var previous: List<OutboxRow> = emptyList()
        outbox.rows.collect { current ->
          mutex.withLock {
            val people = changedPeople(previous, current, database)
            previous = current
            if (people.isEmpty()) return@withLock
            val upserts = mutableListOf<ContactSummaryEntity>()
            val deletions = mutableListOf<String>()
            for (id in people) {
              val summary = personLedger.records(id)?.ledger?.let { summaryOf(it, id) }
              if (summary != null) upserts += summary else deletions += id
            }
            database.withTransaction {
              if (upserts.isNotEmpty()) summaries.upsert(upserts)
              if (deletions.isNotEmpty()) summaries.delete(deletions)
            }
          }
        }
      }

  /** The people named by rows added, removed or state-changed between two outbox emissions. */
  private suspend fun changedPeople(
      previous: List<OutboxRow>,
      current: List<OutboxRow>,
      database: LedgerDatabase,
  ): Set<String> {
    val previousById = previous.associateBy { it.operationId }
    val currentIds = current.mapTo(HashSet()) { it.operationId }
    val changed = mutableListOf<OutboxRow>()
    for (row in current) {
      val before = previousById[row.operationId]
      if (before == null || before.state != row.state) changed += row
    }
    for (row in previous) if (row.operationId !in currentIds) changed += row
    if (changed.isEmpty()) return emptySet()
    // A debt or payment that is itself queued names its owner from the queued create; check both
    // snapshots, so a removed row's owner is still found in the previous one.
    val queuedDebtOwner = mutableMapOf<String, String>()
    val queuedPaymentDebt = mutableMapOf<String, String>()
    for (row in current + previous) {
      when (val payload = row.command.payload()) {
        is DebtCreate -> queuedDebtOwner[row.command.entityId] = payload.contactId
        is PaymentCreate -> queuedPaymentDebt[row.command.entityId] = payload.debtId
        else -> Unit
      }
    }
    val debtOwner: suspend (String) -> String? = { debtId ->
      queuedDebtOwner[debtId] ?: database.debtDao().contactId(debtId)
    }
    val paymentDebt: suspend (String) -> String? = { paymentId ->
      queuedPaymentDebt[paymentId] ?: database.paymentDao().debtId(paymentId)
    }
    return changed.mapNotNull { personOf(it.command, debtOwner, paymentDebt) }.toSet()
  }

  /**
   * Folds one page under the write lock. Room is the only ledger: the stored meta answers the reset
   * and idle-page checks, the fold persists the page's rows, and the summaries of the people it
   * touches are rebuilt from Room in the same transaction. A page from another epoch or store
   * empties the rows, keeps the outbox, and throws [LedgerReset].
   */
  suspend fun applyPage(page: Changes) {
    mutex.withLock {
      val stored = store.currentMeta()
      if (stored != null && (page.storeId != stored.storeId || page.epoch != stored.epoch)) {
        store.reset()
        throw LedgerReset(
            IllegalStateException(
                "page is for store ${page.storeId} epoch ${page.epoch}, not ${stored.storeId} ${stored.epoch}"
            )
        )
      }
      // An idle poll's page carries nothing: skip the fold, the persist, the publish and the
      // compact, so a pass that found nothing new costs no ledger work.
      if (
          stored != null &&
              page.storeId == stored.storeId &&
              page.epoch == stored.epoch &&
              page.events.isEmpty() &&
              page.nextCursor == stored.cursor
      ) {
        return
      }
      val ids = page.workingSetIds()
      val workingSet = store.loadWorkingSet(ids)
      val next = workingSet.apply(page)
      val rows = outbox.snapshot()
      val pending = pendingCommands(rows)
      val failed = failedOperations(rows)
      // After a reset the table is empty, and no page names a person who exists only in the outbox.
      val queued =
          if (stored == null)
              pending.filter { it.type == CommandType.CONTACT_CREATE }.map { it.entityId }
          else emptyList()
      val touched = touchedPeople(page, next) + queued
      store.persistWithSummariesFromRoom(workingSet, next, page, touched) { contactId ->
        val personState = store.loadPerson(contactId) ?: return@persistWithSummariesFromRoom null
        val personView = personState.view(pending, failed, session.selfInstallationId, "")
        summaryOf(personView, contactId)
      }
      outbox.compact(store::present)
    }
  }

  /**
   * The people one page touches: accepted events only, reversed via the new state. A person not in
   * the next view (archived, deleted, or excluded) has no summary, so the caller deletes the row.
   */
  private fun touchedPeople(page: Changes, next: LedgerState): Set<String> {
    val debtToContact = next.debts.mapValues { it.value.contactId }
    val paymentToDebt = next.payments.mapValues { it.value.debtId }
    val touched = LinkedHashSet<String>()
    for (event in page.events) {
      if (event.kind == ChangeEvent.REJECTED_KIND || event.result.status == ReceiptStatus.REJECTED)
          continue
      val command = LedgerJson.decodeFromJsonElement(LedgerCommand.serializer(), event.command)
      when (val payload = command.payload()) {
        is ContactCreate -> touched += command.entityId
        is ContactUpdate -> touched += payload.contactId
        is ContactArchive -> touched += payload.contactId
        is ContactRestore -> touched += payload.contactId
        is ContactDelete -> touched += payload.contactId
        is DebtCreate -> touched += payload.contactId
        is DebtCorrect -> debtToContact[payload.debtId]?.let { touched += it }
        is DebtOffset -> debtToContact[payload.receivableDebtId]?.let { touched += it }
        is PaymentCreate -> debtToContact[payload.debtId]?.let { touched += it }
        is PaymentReverse ->
            paymentToDebt[payload.paymentId]?.let { debtId ->
              debtToContact[debtId]?.let { touched += it }
            }
      }
    }
    return touched
  }

  suspend fun addContact(
      name: String,
      phone: String?,
      category: String?,
      note: String?,
      firstDebt: FirstDebt?,
      kind: ContactKind = ContactKind.CUSTOMER,
      whatsappCode: String? = null,
  ): Result<String> = guarded {
    val origin = origin()
    requireOfflineAllowed()
    val at = Instant.now()
    val contact =
        Commands.contactCreate(
            origin,
            at,
            name,
            wirePhone(phone),
            category,
            note,
            kind,
            whatsappCode,
        )
    val commands = mutableListOf(contact)
    if (firstDebt != null) {
      commands +=
          Commands.debtCreate(
              origin,
              at,
              contact.entityId,
              kind.debtDirection,
              firstDebt.amount,
              firstDebt.note,
              firstDebt.dueDate,
              dependsOn = listOf(contact.operationId),
          )
    }
    enqueue(commands)
    contact.entityId
  }

  suspend fun updateContact(
      contactId: String,
      name: String,
      phone: String?,
      category: String?,
      note: String?,
      kind: ContactKind = ContactKind.CUSTOMER,
      whatsappCode: String? = null,
  ): Result<Unit> = guarded {
    val origin = origin()
    requireOnline()
    val contact = personContact(contactId)
    enqueue(
        listOf(
            Commands.contactUpdate(
                origin,
                Instant.now(),
                contactId,
                nextVersionAfter(contact, contactId),
                name,
                wirePhone(phone),
                category,
                note,
                kind,
                whatsappCode,
                dependsOn = contact.dependency(),
                entityId = contactId,
            )
        )
    )
  }

  suspend fun archiveContact(contactId: String, reason: String): Result<Unit> = guarded {
    val origin = origin()
    requireOnline()
    val view = personViewOf(contactId)
    val contact = view.contactOrThrow(contactId)
    if (view.debts.any { it.contactId == contactId && it.remaining > Cents.ZERO }) {
      throw Refusal.Invalid("سدّد الدين المتبقي على هذا الشخص أولاً.")
    }
    enqueue(
        listOf(
            Commands.contactArchive(
                origin,
                Instant.now(),
                contactId,
                nextVersionAfter(contact, contactId),
                reason,
                dependsOn = contact.dependency(),
                entityId = contactId,
            )
        )
    )
  }

  suspend fun restoreContact(contactId: String, reason: String): Result<Unit> = guarded {
    val origin = origin()
    requireOnline()
    val contact = personContact(contactId)
    enqueue(
        listOf(
            Commands.contactRestore(
                origin,
                Instant.now(),
                contactId,
                nextVersionAfter(contact, contactId),
                reason,
                dependsOn = contact.dependency(),
                entityId = contactId,
            )
        )
    )
  }

  /**
   * Drops a new person the server refused, with the debts saved alongside them: the debts' outbox
   * rows first (they depend on the person), then the person's. An accepted person is never touched.
   */
  suspend fun discardDraft(contactId: String): Result<Unit> = guarded {
    // The confirmed half decides: a stored row (visible or archived) is the server's now.
    val known = store.loadPerson(contactId)?.contacts?.containsKey(contactId) == true
    val rows = outbox.snapshot()
    val draft = rows.firstOrNull {
      it.entityId == contactId &&
          it.command.payload() is ContactCreate &&
          (it.state == OutboxState.REJECTED || it.state == OutboxState.UNSENDABLE)
    }
    if (known || draft == null) throw Refusal.Unknown
    val dependents = rows.filter { draft.operationId in it.command.dependsOn }
    for (row in dependents + draft) {
      val result = outbox.dismiss(row.operationId)
      if (result != DismissResult.Dismissed) throw Refusal.Invalid(result.toString())
    }
  }

  /** Permanent deletion is online-only and only targets a zero-balance contact in the trash. */
  suspend fun deleteContact(contactId: String, reason: String): Result<Unit> = guarded {
    val origin = origin()
    requireOnline()
    // The stored rows decide: the trash's contact is always confirmed, and the projection needs
    // the raw one-person state, which loadPerson returns in one transaction.
    val stored = store.loadPerson(contactId)
    val archived =
        stored
            ?.view(emptyList(), emptySet(), session.selfInstallationId, "")
            ?.archivedContacts
            ?.firstOrNull { it.id == contactId }
            ?: throw Refusal.Invalid("انقل الشخص إلى سلة المحذوفات أولًا.")
    // Same projection on the one person's rows as today on the whole state: un-archive, then ask
    // the queued payments whether any debt is still open.
    val rows = outbox.snapshot()
    val visible =
        stored
            .copy(
                contacts =
                    stored.contacts.mapValues { (id, row) ->
                      if (id == contactId) row.copy(archived = false) else row
                    }
            )
            .view(pendingCommands(rows), failedOperations(rows), session.selfInstallationId, "")
    if (visible.debts.any { it.contactId == contactId && it.remaining > Cents.ZERO })
        throw Refusal.Invalid("سدّد الرصيد قبل الحذف النهائي.")
    enqueue(
        listOf(
            Commands.contactDelete(
                origin,
                Instant.now(),
                contactId,
                archived.version,
                reason,
                entityId = contactId,
            )
        )
    )
  }

  private fun origin(): Origin = session.origin() ?: throw Refusal.NotReady

  private fun requireOnline() {
    if (!session.isOnline()) throw Refusal.OnlineOnly
  }

  private fun requireOfflineAllowed() {
    if (!session.isOnline() && !session.offlineAllowed()) throw Refusal.OfflineNotAllowed
  }

  /**
   * The one person's view a write checks against, read from Room with the queue on top, by the same
   * projection rules the whole-store view used (ticket 167). A person who exists only in the queue
   * — no confirmed rows yet, so no `ledger_meta` — projects on the empty state exactly as the
   * whole-store projection did. That is also why the writes no longer wait for `load()`: Room holds
   * the rows even before the in-memory state does.
   */
  private suspend fun personViewOf(contactId: String): LedgerView {
    val rows = outbox.snapshot()
    val state = store.loadPerson(contactId) ?: LedgerState.EMPTY
    return state.view(
        pendingCommands(rows),
        failedOperations(rows),
        session.selfInstallationId,
        "",
    )
  }

  /**
   * The Contact a write names, from either side of the trash; anything else is [Refusal.Unknown].
   */
  private suspend fun personContact(contactId: String): ContactView =
      personViewOf(contactId).contactOrThrow(contactId)

  /**
   * The version the server will expect after [contact]'s confirmed state: one more for every edit
   * of that Contact still queued. Without this, two edits before a pull both name the version the
   * first one consumes, and the server refuses the second.
   */
  private suspend fun nextVersionAfter(contact: ContactView, contactId: String): Long =
      contact.version +
          outbox.snapshot().count {
            it.state == OutboxState.QUEUED && it.entityId == contactId && it.type in EDITS
          }

  /**
   * The duplicate the contact form must stop on, having [excludeContactId] as the form's own
   * person: one Room query (the stored contacts carrying [phone]) joined with the queued
   * `contact.create`/`contact.update` payloads that also give [phone]. Each candidate loads as
   * their own person, and the pending contact edits get folded on top — the projected view carries
   * none of them, because contact edits are only ever sent online and never projected — so a queued
   * phone change away from [phone] or a queued archive removes the match. An un-queued restore (or
   * any other edit) is folded no further than today's whole-view check. Any one match is fine: the
   * dialog shows one name.
   */
  suspend fun duplicateContact(phone: String, excludeContactId: String?): ContactView? {
    val queued =
        outbox.snapshot().filter {
          it.state == OutboxState.QUEUED || it.state == OutboxState.ACCEPTED
        }
    val queuedPhones = HashMap<String, MutableList<String?>>()
    val queuedArchives = HashSet<String>()
    for (row in queued) {
      when (val payload = row.command.payload()) {
        is ContactCreate ->
            queuedPhones.getOrPut(row.entityId) { mutableListOf() }.add(payload.phone)
        is ContactUpdate ->
            queuedPhones.getOrPut(row.entityId) { mutableListOf() }.add(payload.phone)
        is ContactArchive -> queuedArchives += row.entityId
        else -> Unit
      }
    }
    for (id in (store.contactIdsWithPhone(phone) + queuedPhones.keys).distinct()) {
      if (id == excludeContactId) continue
      val view = personViewOf(id)
      // Visible exactly when the projection holds them among the non-archived contacts, as
      // today's whole-view check does; an archived (or archived-anyway) person is not one.
      val contact = view.contact(id)
      if (contact == null || id in queuedArchives) continue
      var phoneNow = contact.phone
      for (queuedPhone in queuedPhones[id].orEmpty()) phoneNow = queuedPhone.orEmpty()
      if (phoneNow == phone) return contact
    }
    return null
  }

  /**
   * The Contact a write names, from either side of the trash; anything else is [Refusal.Unknown].
   */
  private fun LedgerView.contactOrThrow(contactId: String): ContactView =
      contact(contactId)
          ?: archivedContacts.firstOrNull { it.id == contactId }
          ?: throw Refusal.Unknown

  /** A queued parent must be accepted before its child; a confirmed one needs no dependency. */
  private fun ContactView.dependency(): List<String> =
      if (pending) listOf(operationId) else emptyList()

  private suspend fun enqueue(commands: List<LedgerCommand>) {
    outbox.append(commands)
    session.onQueued()
  }

  /** UI formatting must finish before queueing a person and any dependent financial entries. */
  private fun wirePhone(value: String?): String? {
    val phone = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    require(phone.matches(Regex("05[0-9]{8}"))) {
      "راجع رقم الهاتف؛ يجب أن يكون رقم جوال من 10 أرقام يبدأ بـ05."
    }
    return phone
  }

  private suspend fun <T> guarded(block: suspend () -> T): Result<T> =
      try {
        Result.success(block())
      } catch (refusal: Refusal) {
        Result.failure(refusal)
      } catch (invalid: IllegalArgumentException) {
        Result.failure(Refusal.Invalid(invalid.message ?: ""))
      }

  private companion object {
    /** Commands that advance a Contact's version on the server. */
    val EDITS =
        setOf(CommandType.CONTACT_UPDATE, CommandType.CONTACT_ARCHIVE, CommandType.CONTACT_RESTORE)
  }
}
