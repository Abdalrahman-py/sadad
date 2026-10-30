package com.sadad.app.data.ledger

import android.os.Build
import android.os.Trace
import com.sadad.app.data.db.LedgerStore
import com.sadad.ledger.Cents
import com.sadad.ledger.Changes
import com.sadad.ledger.CommandType
import com.sadad.ledger.Commands
import com.sadad.ledger.ContactKind
import com.sadad.ledger.ContactView
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerState
import com.sadad.ledger.LedgerView
import com.sadad.ledger.Origin
import com.sadad.ledger.PaymentAllocation
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.view
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    scope: CoroutineScope,
) {
  private val state = MutableStateFlow(LedgerState.EMPTY)
  private val mutex = Mutex()

  val contactRecords: StateFlow<ContactRecords?> =
      combine(state, outbox.rows, session.staffName) { current, rows, staff ->
            contactRecords(project(current, rows, staff), rows)
          }
          .flowOn(Dispatchers.Default)
          .stateIn(scope, SharingStarted.Eagerly, null)

  val view: StateFlow<LedgerView?> =
      contactRecords.map { it?.ledger }.stateIn(scope, SharingStarted.Eagerly, null)

  /** Reads the confirmed rows once, when the store opens. */
  suspend fun load() {
    state.value = store.load()
  }

  /**
   * Folds one page under the write lock. A page from another epoch or store empties the state and
   * the rows, keeps the outbox, and throws [LedgerReset]; any other refusal leaves the state alone.
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
      val next = old.apply(page)
      store.persist(old, next, page)
      state.value = next
      outbox.compact(next)
    }
  }

  suspend fun addContact(
      name: String,
      phone: String?,
      category: String?,
      note: String?,
      firstDebt: FirstDebt?,
      kind: ContactKind = ContactKind.CUSTOMER,
  ): Result<String> = guarded {
    val origin = origin()
    requireOfflineAllowed()
    val at = Instant.now()
    val contact = Commands.contactCreate(origin, at, name, phone, category, note, kind)
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
  ): Result<Unit> = guarded {
    val origin = origin()
    requireOnline()
    val contact = currentView().contact(contactId)
    enqueue(
        listOf(
            Commands.contactUpdate(
                origin,
                Instant.now(),
                contactId,
                nextVersion(contactId),
                name,
                phone,
                category,
                note,
                kind,
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
    val contact = current.contact(contactId)
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
    val contact = currentView().contact(contactId)
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

  /** The view a write checks against, with the queue included and before it changes. */
  private suspend fun currentView(): LedgerView = project(state.value, outbox.snapshot(), "")

  /** One projection for the flow and for a write's checks, so both see the same rules. */
  private fun project(current: LedgerState, rows: List<OutboxRow>, staff: String): LedgerView =
      current.view(
          pendingCommands(rows),
          failedOperations(rows),
          session.selfInstallationId,
          staff,
      )

  /**
   * The Contact a write names, from either side of the trash; anything else is [Refusal.Unknown].
   */
  private fun LedgerView.contact(contactId: String): ContactView =
      contacts.firstOrNull { it.id == contactId }
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
    val confirmed = currentView().contact(contactId).version
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
