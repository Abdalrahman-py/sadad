package com.sadad.app.data.ledger

import com.sadad.app.data.crypto.Sealed
import com.sadad.app.data.db.OutboxAnswer
import com.sadad.app.data.db.OutboxDao
import com.sadad.app.data.db.OutboxEntity
import com.sadad.ledger.CommandType
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerJson
import com.sadad.ledger.LedgerState
import com.sadad.ledger.OperationsRequest
import com.sadad.ledger.Receipt
import com.sadad.ledger.ReceiptStatus
import com.sadad.ledger.ServerTime
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The outbox row states; [column] is the text Room stores. */
enum class OutboxState(val column: String) {
  QUEUED("queued"),
  ACCEPTED("accepted"),
  REJECTED("rejected"),
  CANCELLED("cancelled"),

  /** This installation can never send the row (another installation wrote it): discard only. */
  UNSENDABLE("unsendable");

  companion object {
    fun of(column: String): OutboxState = entries.first { it.column == column }
  }
}

/** One outbox row with its sealed command opened. */
data class OutboxRow(
    val seq: Long,
    val operationId: String,
    val entityId: String,
    val type: CommandType,
    val state: OutboxState,
    val code: String?,
    val createdAt: Instant,
    val recoveryPermit: String?,
    val command: LedgerCommand,
)

sealed interface DismissResult {
  data object Dismissed : DismissResult

  /** [dependentOperationId] still names this row in its `dependsOn`; dismiss it first. */
  data class Blocked(val dependentOperationId: String) : DismissResult

  /** The row is not in a state the owner may dismiss (queued or already accepted). */
  data class NotDismissible(val state: OutboxState) : DismissResult
}

/**
 * The store's unsent commands. A row's payload is sealed with the process data key; the other
 * columns are what sync and the activity log read without opening it. Writes that touch several
 * rows are one Room transaction ([OutboxDao.insertAll] and [OutboxDao.update] wrap their lists).
 */
class Outbox(private val dao: OutboxDao, private val dataKey: SecretKey) {
  // A command never changes, so opening it once per operationId is enough.
  // shortcut: entries for compacted rows stay for the process; clear the map when a store closes
  // if the queue ever outgrows the process.
  private val decoded = ConcurrentHashMap<String, LedgerCommand>()

  val rows: Flow<List<OutboxRow>> = dao.observe().map { stored -> stored.map { it.toRow() } }

  /** The rows as they are now, for a write that has to check the queue before appending. */
  suspend fun snapshot(): List<OutboxRow> = dao.all().map { it.toRow() }

  /** Writes every command in one transaction, sealed, in queue order. */
  suspend fun append(commands: List<LedgerCommand>) {
    if (commands.isEmpty()) return
    dao.insertAll(commands.map { it.toEntity() })
  }

  /** The next queued commands, oldest first, decoded for a sync batch. */
  suspend fun queued(limit: Int = OperationsRequest.MAX_BATCH): List<LedgerCommand> =
      dao.queued(limit).map { commandOf(it) }

  /**
   * Puts [permit] on the queued rows of [origin] that [operationIds] names — the commands the admin
   * actually approved. A row added after the manifest was exported is left alone: the server would
   * refuse the whole batch for it. Returns how many rows were stamped.
   */
  suspend fun setPermit(origin: String, permit: String, operationIds: Set<String>): Int {
    val ids =
        snapshot()
            .filter {
              it.state == OutboxState.QUEUED &&
                  it.command.type.offline &&
                  it.command.originInstallationId == origin &&
                  it.operationId in operationIds
            }
            .map { it.operationId }
    if (ids.isNotEmpty()) for (chunk in ids.chunked(500)) dao.setRecoveryPermit(chunk, permit)
    return ids.size
  }

  /** Marks the queued rows accepted or rejected in one transaction; a cancelled row stays so. */
  suspend fun settle(receipts: List<Receipt>) {
    if (receipts.isEmpty()) return
    val stored = dao.all().associateBy { it.operationId }
    val answers = receipts.mapNotNull { receipt ->
      val row = stored[receipt.operationId] ?: return@mapNotNull null
      if (row.state != OutboxState.QUEUED.column) return@mapNotNull null
      val state =
          when (receipt.status) {
            ReceiptStatus.ACCEPTED -> OutboxState.ACCEPTED
            ReceiptStatus.REJECTED -> OutboxState.REJECTED
          }
      OutboxAnswer(receipt.operationId, state.column, receipt.code)
    }
    if (answers.isNotEmpty()) dao.settle(answers)
  }

  /**
   * Cancels [operationId] and, transitively, every queued row that depends on a cancelled one, in
   * one statement. Only queued rows are cancelled: an answered row is the server's now.
   */
  suspend fun cancel(operationId: String) {
    val all = snapshot()
    if (all.none { it.operationId == operationId && it.state == OutboxState.QUEUED }) return
    val cancelled = linkedSetOf(operationId)
    var grew = true
    while (grew) {
      grew = false
      for (row in all) {
        if (row.state != OutboxState.QUEUED || row.operationId in cancelled) continue
        if (row.command.dependsOn.any { it in cancelled }) {
          cancelled += row.operationId
          grew = true
        }
      }
    }
    dao.cancel(cancelled.toList())
  }

  /**
   * Names queued rows this installation can never send: they were written under another
   * installation and carry no recovery permit. Without this they would stay queued forever with no
   * action anywhere in the app.
   */
  suspend fun markUnsendable(operationIds: List<String>) {
    if (operationIds.isEmpty()) return
    dao.settle(
        operationIds.map { OutboxAnswer(it, OutboxState.UNSENDABLE.column, FOREIGN_ORIGIN_CODE) }
    )
  }

  /**
   * Deletes a rejected, cancelled or unsendable row that no other row depends on. A dependent
   * blocks the dismissal and is named, so the user dismisses it first. A row that is still queued
   * or already accepted is refused by name rather than reported as dismissed.
   */
  suspend fun dismiss(operationId: String): DismissResult {
    val all = snapshot()
    val row = all.firstOrNull { it.operationId == operationId } ?: return DismissResult.Dismissed
    if (
        row.state != OutboxState.REJECTED &&
            row.state != OutboxState.CANCELLED &&
            row.state != OutboxState.UNSENDABLE
    ) {
      return DismissResult.NotDismissible(row.state)
    }
    val dependent = all.firstOrNull {
      it.operationId != operationId && operationId in it.command.dependsOn
    }
    if (dependent != null) return DismissResult.Blocked(dependent.operationId)
    dao.deleteDismissed(operationId)
    return DismissResult.Dismissed
  }

  /** Deletes the accepted rows whose entity the confirmed [state] now holds. */
  suspend fun compact(state: LedgerState) {
    // Match in Kotlin: binding every id in the state would pass SQLite's 999-variable limit
    // (API < 30) once a store holds that many rows.
    val done =
        dao.all()
            .filter {
              it.state == OutboxState.ACCEPTED.column &&
                  (if (it.type == CommandType.CONTACT_DELETE.wireName) {
                    (commandOf(it).payload() as com.sadad.ledger.ContactDelete).contactId !in
                        state.contacts
                  } else {
                    it.entityId in state.contacts ||
                        it.entityId in state.debts ||
                        it.entityId in state.payments
                  }) &&
                  // The archived Contact already exists before its restore event is pulled.
                  (it.type != CommandType.CONTACT_RESTORE.wireName ||
                      checkNotNull(state.contacts[it.entityId]).version >
                          checkNotNull(commandOf(it).expectedVersion))
            }
            .map { it.operationId }
    for (chunk in done.chunked(500)) dao.deleteAccepted(chunk)
  }

  private fun OutboxEntity.toRow(): OutboxRow {
    val command = commandOf(this)
    return OutboxRow(
        seq,
        operationId,
        entityId,
        command.type,
        OutboxState.of(state),
        code,
        Instant.ofEpochMilli(createdAt),
        recoveryPermit,
        command,
    )
  }

  private fun commandOf(entity: OutboxEntity): LedgerCommand =
      decoded.computeIfAbsent(entity.operationId) {
        val opened = Sealed.open(dataKey, entity.payload, aad(entity.operationId))
        LedgerJson.decodeFromString(
            LedgerCommand.serializer(),
            opened.toString(StandardCharsets.UTF_8),
        )
      }

  private fun LedgerCommand.toEntity(): OutboxEntity =
      OutboxEntity(
          operationId = operationId,
          entityId = entityId,
          type = type.wireName,
          state = OutboxState.QUEUED.column,
          code = null,
          createdAt = ServerTime.parse(createdAt).toEpochMilli(),
          recoveryPermit = null,
          payload = seal(this),
      )

  private fun seal(command: LedgerCommand): ByteArray =
      Sealed.seal(
          dataKey,
          LedgerJson.encodeToString(LedgerCommand.serializer(), command)
              .toByteArray(StandardCharsets.UTF_8),
          aad(command.operationId),
      )

  /** Binds a payload to its row, so swapping two rows' bytes cannot open either. */
  private fun aad(operationId: String): String = "outbox:$operationId"

  private companion object {
    const val FOREIGN_ORIGIN_CODE = "foreign_origin"
  }
}

/** What `view()` projects: the unsettled commands in queue order. */
internal fun pendingCommands(rows: List<OutboxRow>): List<LedgerCommand> =
    rows
        .filter { it.state == OutboxState.QUEUED || it.state == OutboxState.ACCEPTED }
        .map { it.command }

/** The operation ids `view()` must not project because they were rejected or cancelled. */
internal fun failedOperations(rows: List<OutboxRow>): Set<String> =
    rows
        .filter { it.state == OutboxState.REJECTED || it.state == OutboxState.CANCELLED }
        .map { it.operationId }
        .toSet()
