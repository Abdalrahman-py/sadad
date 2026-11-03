package com.sadad.app.feature.quickadd

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.sadad.app.AppGraph
import com.sadad.app.feature.contactpicker.DeviceContact
import com.sadad.app.feature.contactpicker.DeviceContacts
import com.sadad.app.permissions
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
 * What quick add reads from the open store: the Ledger, the device's permissions and its status.
 */
interface QuickAddSource {
  /** The open store's Ledger view; null while no store is open. */
  val view: Flow<LedgerView?>

  /** The open installation's permissions by name. */
  val permissions: Flow<Map<String, Boolean>>

  /** True when this device is not approved to record. */
  val blocked: Flow<Boolean>

  /** The store as it is now, or null when none is open. */
  fun open(): QuickAddStore?

  /** The phone's contacts, read only once the permission is granted. */
  val contacts: DeviceContacts
    get() = NO_CONTACTS

  /** Whether reading the phone's contacts is allowed right now. */
  fun contactsGranted(): Boolean = false

  /** The open store's person limit from its verified lease; null without one. */
  fun contactLimit(): Int? = null

  /** The words for a server refusal code, the same the rest of the app shows. */
  fun refusalText(code: String): String = code
}

private val NO_CONTACTS =
    object : DeviceContacts {
      override suspend fun list(): List<DeviceContact> = emptyList()
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
/** [appContext] answers the contacts permission; without one the phone's contacts stay unread. */
fun AppGraph.quickAddSource(appContext: Context? = null): QuickAddSource {
  val graph = this
  return object : QuickAddSource {
    override val view: Flow<LedgerView?> =
        graph.current.flatMapLatest { scope -> scope?.repository?.view ?: flowOf(null) }

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

    override val contacts: DeviceContacts = graph.deviceContacts()

    override fun contactsGranted(): Boolean =
        appContext != null &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED

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
