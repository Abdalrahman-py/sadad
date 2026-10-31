package com.sadad.app.feature.trash

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.R
import com.sadad.app.data.ledger.LedgerRepository
import com.sadad.app.data.ledger.Outbox
import com.sadad.app.data.ledger.OutboxRow
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.data.ledger.Refusal
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.feature.notifications.Reminder
import com.sadad.ledger.CommandType
import com.sadad.ledger.ContactView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One row of the trash: an archived Contact or a dismissed due-date notification. */
sealed interface TrashItem {
  val id: String

  data class Contact(val contact: ContactView) : TrashItem {
    override val id: String
      get() = contact.id
  }

  data class Notification(val reminder: Reminder) : TrashItem {
    override val id: String
      get() = reminder.id
  }
}

data class TrashState(
    val items: List<TrashItem> = emptyList(),
    /** The item whose "delete for good" confirm dialog is open. */
    val confirm: TrashItem? = null,
    val message: String? = null,
    /** Contacts with a restore or final deletion awaiting its changes page. */
    val restoring: Set<String> = emptySet(),
)

/**
 * Archived Contacts and dismissed reminders. Contact actions are shared ledger commands; reminder
 * dismissal is local to this phone.
 */
class TrashViewModel(
    private val repository: LedgerRepository,
    private val outbox: Outbox,
    private val reminders: DueReminders,
    private val hidden: TrashHidden,
    storeUserId: String,
    context: Context,
) : ViewModel() {
  private val appContext = context.applicationContext
  private val errors = com.sadad.app.data.net.ErrorMessages(appContext)
  private val mutable = MutableStateFlow(TrashState())
  val state: StateFlow<TrashState> = mutable.asStateFlow()

  init {
    viewModelScope.launch {
      combine(repository.view, reminders.dismissed(storeUserId), hidden.ids, outbox.rows) {
              view,
              dismissed,
              hiddenIds,
              rows ->
            val contacts = view?.archivedContacts.orEmpty().map { TrashItem.Contact(it) }
            val notifications = dismissed.map { TrashItem.Notification(it) }
            val items = contacts + notifications.filter { it.id !in hiddenIds }
            Triple(
                items,
                pendingRestores(rows),
                rows
                    .lastOrNull { it.type == CommandType.CONTACT_DELETE }
                    ?.takeIf { it.state == OutboxState.REJECTED }
                    ?.code
                    ?.let(errors::text),
            )
          }
          .collect { (items, pending, failure) ->
            mutable.update {
              it.copy(items = items, restoring = pending, message = failure ?: it.message)
            }
          }
    }
  }

  private val inFlight = mutableSetOf<String>()

  fun askDelete(item: TrashItem) = mutable.update { it.copy(confirm = item) }

  fun dismissDelete() = mutable.update { it.copy(confirm = null) }

  /**
   * The outbox guard survives screen recreation and accepted receipts awaiting their pull page. The
   * in-flight guard covers rapid taps before the queued row reaches the flow.
   */
  fun restore(item: TrashItem): Job? {
    if (item.id in mutable.value.restoring || !inFlight.add(item.id)) return null
    return viewModelScope.launch {
      try {
        when (item) {
          is TrashItem.Contact -> {
            if (item.id in pendingRestores(outbox.snapshot())) return@launch
            repository
                .restoreContact(item.contact.id, RESTORE_REASON)
                .fold(
                    onSuccess = {
                      mutable.update {
                        it.copy(message = appContext.getString(R.string.trash_restore_queued))
                      }
                    },
                    onFailure = { error ->
                      mutable.update { it.copy(message = refusalText(error)) }
                    },
                )
          }
          is TrashItem.Notification -> {
            reminders.restore(item.reminder.id)
            mutable.update { it.copy(message = appContext.getString(R.string.trash_restored)) }
          }
        }
      } finally {
        inFlight.remove(item.id)
      }
    }
  }

  /** Contact deletion must be accepted by the server, never hidden on this phone only. */
  fun deleteForGood(item: TrashItem) {
    if (
        mutable.value.confirm?.id != item.id ||
            item.id in mutable.value.restoring ||
            !inFlight.add(item.id)
    )
        return
    viewModelScope.launch {
      try {
        if (item is TrashItem.Contact && item.id in pendingRestores(outbox.snapshot()))
            return@launch
        when (item) {
          is TrashItem.Contact ->
              repository
                  .deleteContact(item.contact.id, "حذف نهائي من سلة المحذوفات")
                  .fold(
                      onSuccess = {
                        mutable.update {
                          it.copy(
                              confirm = null,
                              message = appContext.getString(R.string.trash_delete_queued),
                          )
                        }
                      },
                      onFailure = { error ->
                        mutable.update {
                          it.copy(confirm = null, message = refusalText(error, deleting = true))
                        }
                      },
                  )
          is TrashItem.Notification -> {
            hidden.hide(item.id)
            mutable.update { it.copy(confirm = null) }
          }
        }
      } finally {
        inFlight.remove(item.id)
      }
    }
  }

  private fun pendingRestores(rows: List<OutboxRow>): Set<String> =
      rows
          .filter {
            it.type in setOf(CommandType.CONTACT_RESTORE, CommandType.CONTACT_DELETE) &&
                (it.state == OutboxState.QUEUED || it.state == OutboxState.ACCEPTED)
          }
          .mapTo(mutableSetOf()) { it.entityId }

  private fun refusalText(error: Throwable, deleting: Boolean = false): String =
      when (error) {
        is Refusal.OnlineOnly,
        is Refusal.OfflineNotAllowed ->
            appContext.getString(
                if (deleting) R.string.trash_delete_failed else R.string.trash_restore_failed
            )
        is Refusal.Invalid ->
            error.message
                ?: appContext.getString(
                    if (deleting) R.string.trash_delete_failed else R.string.trash_restore_failed
                )
        else ->
            appContext.getString(
                if (deleting) R.string.trash_delete_failed else R.string.trash_restore_failed
            )
      }

  private companion object {
    const val RESTORE_REASON = "استعادة من سلة المحذوفات"
  }
}
