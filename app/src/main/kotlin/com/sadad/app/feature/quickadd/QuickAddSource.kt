package com.sadad.app.feature.quickadd

import com.sadad.app.AppGraph
import com.sadad.app.data.db.ContactSummaryEntity
import com.sadad.app.permissions
import com.sadad.ledger.ContactView
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerView
import com.sadad.ledger.Origin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * What quick add reads from the open store: the stored summary, the device's permissions and its
 * status. Room is the only ledger (ticket 168): the picker reads the summary once it is ready, and
 * shows its loading state until then — no ledger view is read anywhere.
 */
interface QuickAddSource {
  /**
   * The stored per-person summary rows; null while no store is open. The picker reads these once
   * [summaryReady] is true, and shows its loading state before.
   */
  val summaries: Flow<List<ContactSummaryEntity>?>
    get() = flowOf(null)

  /** True once the one-time summary fill ended (ticket 160). */
  val summaryReady: Flow<Boolean>
    get() = flowOf(false)

  /**
   * The trash's contacts, so a number that belongs to someone in the archive is told from one that
   * can be imported.
   */
  val archived: Flow<List<ContactView>>
    get() = flowOf(emptyList())

  /** The chosen person's own ledger view, queued commands projected; that is what a save reads. */
  fun personView(contactId: String): Flow<LedgerView?> = flowOf(null)

  /** The open installation's permissions by name. */
  val permissions: Flow<Map<String, Boolean>>

  /** True when this device is not approved to record. */
  val blocked: Flow<Boolean>

  /** The store as it is now, or null when none is open. */
  fun open(): QuickAddStore?

  /** The open store's person limit from its verified lease; null without one. */
  fun contactLimit(): Int? = null

  /** The words for a server refusal code, the same the rest of the app shows. */
  fun refusalText(code: String): String = code
}

/** The one open store, for the entry quick add records. */
interface QuickAddStore {
  /** The store's jobs, not the screen's: the undo window must end in a queued entry. */
  val jobs: CoroutineScope

  /** Where new commands say they come from; null while the installation may not write. */
  fun origin(): Origin?

  /** Whether the device has a network now; a save without one waits in the outbox. */
  fun isOnline(): Boolean

  /** Queues [commands] in the outbox and asks for a sync. */
  suspend fun append(commands: List<LedgerCommand>)
}

@OptIn(ExperimentalCoroutinesApi::class)
fun AppGraph.quickAddSource(): QuickAddSource {
  val graph = this
  return object : QuickAddSource {
    override val summaries: Flow<List<ContactSummaryEntity>?> =
        graph.current.flatMapLatest { scope ->
          scope?.let { it.database.contactSummaryDao().observe() } ?: flowOf(null)
        }

    override val summaryReady: Flow<Boolean> =
        graph.current.flatMapLatest { scope ->
          scope?.repository?.summaryReady ?: flowOf(false)
        }

    override val archived: Flow<List<ContactView>> =
        graph.current.flatMapLatest { scope ->
          scope?.personLedger?.archivedContacts() ?: flowOf(emptyList())
        }

    override fun personView(contactId: String): Flow<LedgerView?> =
        graph.current.flatMapLatest { scope ->
          scope?.personLedger?.observe(contactId)?.map { it?.ledger } ?: flowOf(null)
        }

    override val permissions: Flow<Map<String, Boolean>> = graph.permissions()

    /**
     * Only an unapproved device is barred: the lease gates the send, which is the sync pass's call,
     * so a save without network or lease still queues.
     */
    override val blocked: Flow<Boolean> =
        graph.current.flatMapLatest { scope ->
          if (scope == null) {
            flowOf(true)
          } else {
            graph.accounts.accounts.map {
              val status =
                  it.firstOrNull { account -> account.storeUserId == scope.account.storeUserId }
                      ?.installation
                      ?.status
              status != null && status != "active"
            }
          }
        }

    override fun contactLimit(): Int? =
        graph.current.value?.account?.let { graph.leaseFor(it.storeUserId).contactLimit(it) }

    override fun refusalText(code: String): String = graph.errorMessages.text(code)

    override fun open(): QuickAddStore? =
        graph.current.value?.let { scope ->
          object : QuickAddStore {
            override val jobs = scope.jobs

            override fun origin() = scope.origin()

            override fun isOnline() = scope.isOnline()

            override suspend fun append(commands: List<LedgerCommand>) {
              scope.outbox.append(commands)
              scope.onQueued()
            }
          }
        }
  }
}
