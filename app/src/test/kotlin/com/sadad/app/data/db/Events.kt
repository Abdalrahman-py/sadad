package com.sadad.app.data.db

import com.sadad.ledger.ChangeEvent
import com.sadad.ledger.Changes
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerJson
import com.sadad.ledger.Origin
import com.sadad.ledger.Receipt
import com.sadad.ledger.ReceiptStatus
import java.time.Instant
import kotlinx.serialization.json.jsonObject

/**
 * Builds `changes` events and pages for these tests out of the [Commands] builders.
 * `:ledger-core`'s own test helpers are not visible to `:app`.
 */
object Events {
  const val STORE_ID = 42L
  const val EPOCH = "0d1e2f3a-4b5c-4d6e-8f7a-8b9c0d1e2f3a"
  const val INSTALLATION_ID = "5e0f2a1b-3c4d-4e5f-8a6b-7c8d9e0f1a2b"
  const val USER_ID = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
  const val SERVER_TIME = "2026-10-16T09:00:00.123456+00:00"

  val origin = Origin(INSTALLATION_ID, generation = 1, epoch = EPOCH)

  /** One accepted event for [command] at [sequence], as `27_changes.sql` would return it. */
  fun accepted(command: LedgerCommand, sequence: Long): ChangeEvent {
    val commandJson = LedgerJson.encodeToJsonElement(LedgerCommand.serializer(), command).jsonObject
    val receipt =
        Receipt(
            operationId = command.operationId,
            entityId = command.entityId,
            status = ReceiptStatus.ACCEPTED,
            code = null,
            sequence = sequence,
            epoch = EPOCH,
            serverTime = SERVER_TIME,
        )
    return ChangeEvent(
        sequence = sequence,
        operationId = command.operationId,
        kind = command.type.wireName,
        entityId = command.entityId,
        userId = USER_ID,
        installationId = INSTALLATION_ID,
        generation = 1,
        command = commandJson,
        result = receipt,
        createdAt = SERVER_TIME,
    )
  }

  /** `cursor` is the store's latest sequence, which can be ahead of the page's own last event. */
  fun page(
      events: List<ChangeEvent>,
      cursor: Long = events.lastOrNull()?.sequence ?: 0,
      nextCursor: Long = events.lastOrNull()?.sequence ?: 0,
  ): Changes = Changes(1, STORE_ID, EPOCH, cursor, nextCursor, hasMore = false, "ILS", events)

  /** A fixed time per step, so rows sort predictably. */
  fun at(minute: Int): Instant = Instant.parse("2026-10-16T09:00:00Z").plusSeconds(60L * minute)
}
