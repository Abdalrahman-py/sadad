package com.sadad.app.feature.history

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sadad.app.AppGraph
import com.sadad.app.R
import com.sadad.app.data.db.EventEntity
import com.sadad.app.data.ledger.DismissResult
import com.sadad.app.data.ledger.OutboxRow
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.data.session.StoreScope
import com.sadad.ledger.ChangeEvent
import com.sadad.ledger.CommandType
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerJson
import com.sadad.ledger.ReceiptStatus
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The ledger action each command type shows as, with the Java log's Arabic names. */
enum class HistoryAction(@StringRes val label: Int) {
  CONTACT_CREATE(R.string.history_action_contact_create),
  CONTACT_UPDATE(R.string.history_action_contact_update),
  CONTACT_ARCHIVE(R.string.history_action_contact_archive),
  CONTACT_RESTORE(R.string.history_action_contact_restore),
  DEBT_CREATE(R.string.history_action_debt_create),
  DEBT_CORRECT(R.string.history_action_debt_correct),
  PAYMENT_CREATE(R.string.history_action_payment_create),
  PAYMENT_REVERSE(R.string.history_action_payment_reverse),
  /** A kind this build cannot name, e.g. an operation the server logged for another version. */
  OTHER(R.string.history_action_other);

  companion object {
    fun of(type: CommandType?): HistoryAction =
        when (type) {
          CommandType.CONTACT_CREATE -> CONTACT_CREATE
          CommandType.CONTACT_UPDATE -> CONTACT_UPDATE
          CommandType.CONTACT_ARCHIVE -> CONTACT_ARCHIVE
          CommandType.CONTACT_RESTORE -> CONTACT_RESTORE
          CommandType.DEBT_CREATE -> DEBT_CREATE
          CommandType.DEBT_CORRECT -> DEBT_CORRECT
          CommandType.PAYMENT_CREATE -> PAYMENT_CREATE
          CommandType.PAYMENT_REVERSE -> PAYMENT_REVERSE
          null -> OTHER
        }
  }
}

/** One folded ledger event, as the activity tab lists it. */
data class ActivityEntry(
    val sequence: Long,
    val action: HistoryAction,
    val installationId: String,
    val at: Instant,
    /** The Arabic refusal text when the server rejected this operation; null when accepted. */
    val refused: String?,
    /** This phone's installation sent it. */
    val mine: Boolean,
)

/** A `rejected` or `cancelled` row of this phone's outbox, still waiting to be dismissed. */
data class UnacceptedEntry(
    val operationId: String,
    val action: HistoryAction,
    val state: OutboxState,
    val at: Instant,
    /** The code's Arabic text for a rejected row; null for a cancelled one. */
    val refused: String?,
    /** Set when another outbox row still depends on this one, so it cannot be dismissed first. */
    val blockedBy: String? = null,
)

data class ActivityLogState(
    val entries: List<ActivityEntry> = emptyList(),
    val unaccepted: List<UnacceptedEntry> = emptyList(),
    val loaded: Boolean = false,
)

/**
 * The activity tab: the newest [SHOWN] folded events from the store's `events` table, newest first,
 * with the action, the device that sent it and the Arabic reason when the server refused it, plus
 * this phone's own rows the server rejected or the user cancelled, which can be dismissed one by
 * one — a row another one depends on waits for its dependent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityLogViewModel(private val graph: AppGraph) : ViewModel() {
  /** Rows a dismissal refused, mapped to the dependent that has to go first. */
  private val blocked = MutableStateFlow<Map<String, String>>(emptyMap())

  val state: StateFlow<ActivityLogState> =
      graph.current
          .flatMapLatest { scope ->
            if (scope == null) flowOf(ActivityLogState())
            else
                combine(scope.repository.view, scope.outbox.rows, blocked) { _, rows, blocked ->
                      rows to blocked
                    }
                    .mapLatest { (rows, blocked) -> load(scope, rows, blocked) }
          }
          .stateIn(viewModelScope, SharingStarted.Eagerly, ActivityLogState())

  /** Removes a refused or cancelled row; one that another row still depends on stays. */
  fun dismiss(operationId: String) {
    val scope = graph.current.value ?: return
    viewModelScope.launch {
      val result = scope.outbox.dismiss(operationId)
      if (result is DismissResult.Blocked) {
        blocked.update { it + (operationId to result.dependentOperationId) }
      } else {
        blocked.update { it - operationId }
      }
    }
  }

  private suspend fun load(
      scope: StoreScope,
      rows: List<OutboxRow>,
      blocked: Map<String, String>,
  ): ActivityLogState {
    val events = withContext(Dispatchers.IO) { scope.database.eventDao().all() }
    val self = scope.selfInstallationId
    return ActivityLogState(
        entries = events.asReversed().take(SHOWN).map { it.toEntry(self) },
        unaccepted =
            rows
                .filter {
                  it.state == OutboxState.REJECTED ||
                      it.state == OutboxState.CANCELLED ||
                      it.state == OutboxState.UNSENDABLE
                }
                .sortedByDescending { it.createdAt }
                .map { row ->
                  UnacceptedEntry(
                      operationId = row.operationId,
                      action = HistoryAction.of(row.type),
                      state = row.state,
                      at = row.createdAt,
                      refused = row.code?.let { graph.errorMessages.text(it) },
                      blockedBy = blocked[row.operationId],
                  )
                },
        loaded = true,
    )
  }

  private fun EventEntity.toEntry(selfInstallationId: String): ActivityEntry {
    val refused =
        if (status == ReceiptStatus.REJECTED) code?.let { graph.errorMessages.text(it) } else null
    return ActivityEntry(
        sequence = sequence,
        action = actionOf(this),
        installationId = installationId,
        at = Instant.ofEpochMilli(createdAt),
        refused = refused,
        mine = installationId == selfInstallationId,
    )
  }

  /**
   * A rejected event's [EventEntity.kind] is `operation.rejected`, so the action comes from the
   * command JSON; a command this build cannot read falls back to [HistoryAction.OTHER].
   */
  private fun actionOf(event: EventEntity): HistoryAction {
    val type =
        when (event.kind) {
          ChangeEvent.REJECTED_KIND -> runCatching {
                LedgerJson.decodeFromString(LedgerCommand.serializer(), event.command).type
              }
                  .getOrNull()
          else -> CommandType.entries.firstOrNull { it.wireName == event.kind }
        }
    return HistoryAction.of(type)
  }

  private companion object {
    /** ponytail: the Java log showed the latest 200; the table keeps 500. */
    const val SHOWN = 200
  }
}
