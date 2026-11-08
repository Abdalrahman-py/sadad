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
import com.sadad.ledger.ConfirmedLedger
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
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerJson
import com.sadad.ledger.LedgerState
import com.sadad.ledger.LedgerView
import com.sadad.ledger.Origin
import com.sadad.ledger.PaymentAllocation
import com.sadad.ledger.PaymentCreate
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.PaymentReverse
import com.sadad.ledger.ReceiptStatus
import com.sadad.ledger.confirmed
import com.sadad.ledger.view
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
 * One per open store: the confirmed [LedgerState], the [Outbox] of commands the server has not
 * settled, and the [view] the screens read. Writes put commands in the outbox; only a folded page
 * changes the state.
 */
class LedgerRepository(
    private val store: LedgerStore,
    private val outbox: Outbox,
    private val session: RepositorySession,
    private val scope: CoroutineScope,
) {
  private val state = MutableStateFlow(LedgerState.EMPTY)

  // The view stays null until the rows are in: the empty state would read as zero balances.
  private val loaded = MutableStateFlow(false)
  private val mutex = Mutex()

  // The summary table is ready when no fill is needed, or when the one-time fill ends.
  private val _summaryReady = MutableStateFlow(false)
  val summaryReady: StateFlow<Boolean> = _summaryReady.asStateFlow()

  // The confirmed half depends on the state and the staff name only, so a queued command, or a
  // write's check, reuses it; a new state drops it. The writes pass no staff name, hence the map.
  private val confirmedFor = HashMap<String, ConfirmedLedger>()
  private var confirmedState: LedgerState? = null

  val contactRecords: StateFlow<ContactRecords?> =
      combine(state, outbox.rows, session.staffName, loaded) { current, rows, staff, ready ->
            if (ready) {
              traced("ledger_project") { contactRecords(project(current, rows, staff), rows) }
            } else {
              null
            }
          }
          .flowOn(Dispatchers.Default)
          .stateIn(scope, SharingStarted.Eagerly, null)

  val view: StateFlow<LedgerView?> =
      contactRecords.map { it?.ledger }.stateIn(scope, SharingStarted.Eagerly, null)

  /**
   * Reads the confirmed rows once, when the store opens. On an updated phone the table is empty and
   * the rows are already here, so the fill runs in the background under [mutex] and opening does
   * not wait; a page sent in the meantime waits on that lock. On failure the lock stays held, so no
   * page writes a partial table and the next open retries.
   */
  suspend fun load() {
    val current = traced("ledger_load") { store.load() }
    state.value = current
    if (current.contacts.isEmpty() || store.summaryCount() > 0) {
      _summaryReady.value = true
      loaded.value = true
      return
    }
    mutex.lock()
    loaded.value = true
    scope.launch(Dispatchers.Default) {
      // A second load that started while this fill ran passes the check above (count still 0) and
      // refills here unless it re-checks under the lock.
      if (store.summaryCount() > 0) {
        _summaryReady.value = true
        mutex.unlock()
      } else {
        val rows = outbox.snapshot()
        val view = confirmedOf(current, "").view(pendingCommands(rows), failedOperations(rows))
        store.fillSummaries(view.contacts.mapNotNull { summaryOf(view, it.id) })
        _summaryReady.value = true
        mutex.unlock()
      }
    }
  }

  /**
   * Returns once [load] has read the rows. Waits in the store's own scope, so a store closed before
   * its rows came in cancels the wait instead of holding a sync pass for ever.
   */
  suspend fun awaitLoaded() {
    if (loaded.value) return
    scope.async { loaded.first { it } }.await()
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
   * Folds one page under the write lock. A page from another epoch or store empties the state and
   * the rows, keeps the outbox, and throws [LedgerReset]; any other refusal leaves the state alone.
   * The page and the summaries of the people it touches are persisted in one transaction.
   */
  suspend fun applyPage(page: Changes) {
    mutex.withLock {
      val old = state.value
      if (old.storeId != null && (page.storeId != old.storeId || page.epoch != old.epoch)) {
        store.reset()
        state.value = LedgerState.EMPTY
        throw LedgerReset(
            IllegalStateException(
                "page is for store ${page.storeId} epoch ${page.epoch}, not ${old.storeId} ${old.epoch}"
            )
        )
      }
      // An idle poll's page carries nothing: skip the fold, the persist, the publish and the
      // compact, so a pass that found nothing new costs no ledger work.
      if (
          old.storeId != null &&
              page.storeId == old.storeId &&
              page.epoch == old.epoch &&
              page.events.isEmpty() &&
              page.nextCursor == old.cursor
      ) {
        return
      }
      val next = old.apply(page)
      val rows = outbox.snapshot()
      val view = confirmedOf(next, "").view(pendingCommands(rows), failedOperations(rows))
      // After a reset the table is empty, and no page names a person who exists only in the outbox.
      val queued =
          if (old.storeId == null) view.contacts.filter { it.pending }.map { it.id }
          else emptyList()
      val touched = touchedPeople(page, next) + queued
      val upserts = mutableListOf<ContactSummaryEntity>()
      val deletions = mutableListOf<String>()
      for (id in touched) {
        val summary = summaryOf(view, id)
        if (summary != null) upserts += summary else deletions += id
      }
      store.persistWithSummaries(old, next, page, upserts, deletions)
      state.value = next
      outbox.compact(next)
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
    val contact = currentView().contactOrThrow(contactId)
    enqueue(
        listOf(
            Commands.contactUpdate(
                origin,
                Instant.now(),
                contactId,
                nextVersion(contactId),
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
    val current = currentView()
    val contact = current.contactOrThrow(contactId)
    if (current.debts.any { it.contactId == contactId && it.remaining > Cents.ZERO }) {
      throw Refusal.Invalid("سدّد الدين المتبقي على هذا الشخص أولاً.")
    }
    enqueue(
        listOf(
            Commands.contactArchive(
                origin,
                Instant.now(),
                contactId,
                nextVersion(contactId),
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
    val contact = currentView().contactOrThrow(contactId)
    enqueue(
        listOf(
            Commands.contactRestore(
                origin,
                Instant.now(),
                contactId,
                nextVersion(contactId),
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
    val known = state.value.contacts.containsKey(contactId)
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
    val contact =
        currentView().archivedContacts.firstOrNull { it.id == contactId }
            ?: throw Refusal.Invalid("انقل الشخص إلى سلة المحذوفات أولًا.")
    val stored = state.value
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
                contact.version,
                reason,
                entityId = contactId,
            )
        )
    )
  }

  suspend fun addDebt(
      contactId: String,
      direction: Direction,
      amount: Cents,
      note: String?,
      dueDate: String?,
  ): Result<String> = guarded {
    val origin = origin()
    requireOfflineAllowed()
    val contact = currentView().contacts.firstOrNull { it.id == contactId } ?: throw Refusal.Unknown
    val command =
        Commands.debtCreate(
            origin,
            Instant.now(),
            contactId,
            direction,
            amount,
            note,
            dueDate,
            dependsOn = if (contact.pending) listOf(contact.operationId) else emptyList(),
        )
    traced("save_debt") { enqueue(listOf(command)) }
    command.entityId
  }

  suspend fun payDebt(
      debtId: String,
      amount: Cents,
      method: PaymentMethod,
      note: String?,
  ): Result<String> = guarded {
    val origin = origin()
    requireOfflineAllowed()
    val debt = currentView().debts.firstOrNull { it.id == debtId } ?: throw Refusal.Unknown
    val command =
        Commands.paymentCreate(
            origin,
            Instant.now(),
            debtId,
            amount,
            method,
            note,
            dependsOn = if (debt.pending) listOf(debt.operationId) else emptyList(),
        )
    enqueue(listOf(command))
    command.entityId
  }

  /** Splits [amount] over the Contact's open Debts and queues every part in one transaction. */
  suspend fun payContact(
      contactId: String,
      amount: Cents,
      method: PaymentMethod,
      note: String?,
  ): Result<Unit> = guarded {
    val origin = origin()
    requireOfflineAllowed()
    val current = currentView()
    val contact = current.contacts.firstOrNull { it.id == contactId } ?: throw Refusal.Unknown
    val at = Instant.now()
    val commands =
        PaymentAllocation.allocate(current, contactId, amount, contact.kind.debtDirection).map {
            part ->
          Commands.paymentCreate(
              origin,
              at,
              part.debtId,
              part.amount,
              method,
              note,
              dependsOn = if (part.debtPending) listOf(part.debtOperationId) else emptyList(),
          )
        }
    enqueue(commands)
  }

  private fun origin(): Origin = session.origin() ?: throw Refusal.NotReady

  private fun requireOnline() {
    if (!session.isOnline()) throw Refusal.OnlineOnly
  }

  private fun requireOfflineAllowed() {
    if (!session.isOnline() && !session.offlineAllowed()) throw Refusal.OfflineNotAllowed
  }

  /**
   * The view a write checks against, with the queue included and before it changes. It waits for
   * the rows: a store the start route opened may still be reading them, and a check against the
   * empty state would pass a duplicate phone or refuse a contact that exists.
   */
  private suspend fun currentView(): LedgerView {
    awaitLoaded()
    return project(state.value, outbox.snapshot(), "")
  }

  /** One projection for the flow and for a write's checks, so both see the same rules. */
  private fun project(current: LedgerState, rows: List<OutboxRow>, staff: String): LedgerView =
      confirmedOf(current, staff).view(pendingCommands(rows), failedOperations(rows))

  @Synchronized
  private fun confirmedOf(current: LedgerState, staff: String): ConfirmedLedger {
    if (confirmedState !== current) {
      confirmedFor.clear()
      confirmedState = current
    }
    return confirmedFor.getOrPut(staff) { current.confirmed(session.selfInstallationId, staff) }
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

  /**
   * The version the server will expect for the next command on [contactId]: the confirmed version
   * plus one for every edit of that Contact still queued. Without this, two edits before a pull
   * both name the version the first one consumes, and the server refuses the second.
   */
  private suspend fun nextVersion(contactId: String): Long {
    val confirmed = currentView().contactOrThrow(contactId).version
    val queued =
        outbox.snapshot().count {
          it.state == OutboxState.QUEUED && it.entityId == contactId && it.type in EDITS
        }
    return confirmed + queued
  }

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
