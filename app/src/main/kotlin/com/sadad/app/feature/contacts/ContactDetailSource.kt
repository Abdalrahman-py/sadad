package com.sadad.app.feature.contacts

import com.sadad.app.AppGraph
import com.sadad.app.contactRecords
import com.sadad.app.data.ledger.ContactRecords
import com.sadad.app.data.session.Account
import com.sadad.app.permissions
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.Origin
import kotlinx.coroutines.flow.Flow

/** What the Contact page reads from the open store. */
interface ContactDetailSource {
  val records: Flow<ContactRecords?>
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

  fun isOnline(): Boolean

  fun origin(): Origin?

  /** Queues [commands] in the outbox and asks for a sync. */
  suspend fun append(commands: List<LedgerCommand>)
}

fun AppGraph.contactDetailSource(): ContactDetailSource {
  val graph = this
  return object : ContactDetailSource {
    override val records = graph.contactRecords()
    override val permissions = graph.permissions()

    override fun refusalText(code: String) = graph.errorMessages.text(code)

    override fun account() = graph.current.value?.account

    override fun open(): ContactDetailStore? =
        graph.current.value?.let { scope ->
          object : ContactDetailStore {
            override suspend fun archive(contactId: String, reason: String) =
                scope.repository.archiveContact(contactId, reason)

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
