package com.sadad.app.feature.contacts

import com.sadad.app.AppGraph
import com.sadad.app.data.ledger.ContactRecords
import com.sadad.app.data.session.Account
import com.sadad.app.permissions
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.Origin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** What the Contact page reads from the open store. */
interface ContactDetailSource {
  /** The one contact's records; other people's rows are not read for their page. */
  fun records(contactId: String): Flow<ContactRecords?>

  val permissions: Flow<Map<String, Boolean>>

  /** The Arabic sentence for a server refusal [code]. */
  fun refusalText(code: String): String

  /** The open store's account, for the statement's header; null while no store is open. */
  fun account(): Account?

  /** The store as it is now, or null when none is open. */
  fun open(): ContactDetailStore?
}

/** The open store, for the two writes the page makes: the archive and a payment reversal. */
interface ContactDetailStore {
  suspend fun archive(contactId: String, reason: String): Result<Unit>

  /** Drops a refused new person and the debts saved with them; never an accepted person. */
  suspend fun discardDraft(contactId: String): Result<Unit>

  fun isOnline(): Boolean

  fun origin(): Origin?

  /** Queues [commands] in the outbox and asks for a sync. */
  suspend fun append(commands: List<LedgerCommand>)
}

fun AppGraph.contactDetailSource(): ContactDetailSource {
  val graph = this
  return object : ContactDetailSource {
    // The one person the page shows: the loader tickets 165 and 167 reuse.
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun records(contactId: String) =
        graph.current.flatMapLatest { scope ->
          scope?.personLedger?.observe(contactId) ?: flowOf(null)
        }

    override val permissions = graph.permissions()

    override fun refusalText(code: String) = graph.errorMessages.text(code)

    override fun account() = graph.current.value?.account

    override fun open(): ContactDetailStore? =
        graph.current.value?.let { scope ->
          object : ContactDetailStore {
            override suspend fun archive(contactId: String, reason: String) =
                scope.repository.archiveContact(contactId, reason)

            override suspend fun discardDraft(contactId: String) =
                scope.repository.discardDraft(contactId)

            override fun isOnline() = scope.isOnline()

            override fun origin() = scope.origin()

            override suspend fun append(commands: List<LedgerCommand>) {
              scope.outbox.append(commands)
              scope.onQueued()
            }
          }
        }
  }
}
