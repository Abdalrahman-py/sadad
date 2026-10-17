package com.sadad.ledger

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Builds `changes` events and pages for the tests, from the fixtures or from [Commands]. */
object Events {
  const val STORE_ID = 42L
  const val USER_ID = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"
  const val OTHER_INSTALLATION = "1f2e3d4c-5b6a-4978-8d6c-5b4a3f2e1d0c"
  const val SERVER_TIME = "2026-10-16T09:00:00.123456+00:00"

  val origin = Origin(Fixtures.INSTALLATION_ID, generation = 1, epoch = Fixtures.EPOCH)
  val otherOrigin = Origin(OTHER_INSTALLATION, generation = 1, epoch = Fixtures.EPOCH)

  private val operations = Fixtures.read("mobile/operations.json")

  /** The fixture receipt with the fields the server adds (`epoch`, `serverTime`). */
  fun fixtureReceipts(): List<JsonObject> =
      operations.getValue("receipts").jsonArray.map {
        JsonObject(
            it.jsonObject +
                mapOf(
                    "epoch" to JsonPrimitive(Fixtures.EPOCH),
                    "serverTime" to JsonPrimitive(SERVER_TIME),
                )
        )
      }

  fun fixtureCommands(): List<JsonObject> =
      operations.getValue("request").jsonObject.getValue("operations").jsonArray.map {
        it.jsonObject
      }

  /** The three operations.json commands as accepted events, sequences 1 to 3. */
  fun fixtureEvents(): List<ChangeEvent> =
      fixtureCommands().zip(fixtureReceipts()).map { (command, receipt) ->
        val result = LedgerJson.decodeFromJsonElement(Receipt.serializer(), receipt)
        event(command, result, kind = command.getValue("type").jsonPrimitive.content)
      }

  fun accepted(command: LedgerCommand, sequence: Long): ChangeEvent {
    val json = LedgerJson.encodeToJsonElement(LedgerCommand.serializer(), command).jsonObject
    return event(
        json,
        receipt(command.operationId, command.entityId, sequence, ReceiptStatus.ACCEPTED, null),
        kind = json.getValue("type").jsonPrimitive.content,
    )
  }

  /** A rejected event: kind operation.rejected; its command may be one the client cannot read. */
  fun rejected(
      command: JsonObject,
      sequence: Long,
      code: String = "unsupported_operation",
  ): ChangeEvent {
    val operationId = command.getValue("operationId").jsonPrimitive.content
    val entityId = command.getValue("entityId").jsonPrimitive.content
    return event(
        command,
        receipt(operationId, entityId, sequence, ReceiptStatus.REJECTED, code),
        kind = "operation.rejected",
    )
  }

  private fun receipt(
      operationId: String,
      entityId: String,
      sequence: Long,
      status: ReceiptStatus,
      code: String?,
  ) = Receipt(operationId, entityId, status, code, sequence, Fixtures.EPOCH, SERVER_TIME)

  private fun event(command: JsonObject, result: Receipt, kind: String) =
      ChangeEvent(
          sequence = result.sequence,
          operationId = result.operationId,
          kind = kind,
          entityId = result.entityId,
          userId = USER_ID,
          installationId = command.getValue("originInstallationId").jsonPrimitive.content,
          generation = command.getValue("originGeneration").jsonPrimitive.long.toInt(),
          command = command,
          result = result,
          createdAt = SERVER_TIME,
      )

  fun page(
      events: List<ChangeEvent>,
      nextCursor: Long = events.lastOrNull()?.sequence ?: 0,
      cursor: Long = nextCursor,
      epoch: String = Fixtures.EPOCH,
      storeId: Long = STORE_ID,
      schemaVersion: Int = 1,
      currency: String = "ILS",
  ) = Changes(schemaVersion, storeId, epoch, cursor, nextCursor, false, currency, events)

  /** Folds accepted events built from commands, numbered from 1, into one page. */
  fun stateOf(vararg commands: LedgerCommand): LedgerState =
      LedgerState.EMPTY.apply(page(commands.mapIndexed { i, c -> accepted(c, i + 1L) }))

  /** A fixed time per test step, so rows sort predictably. */
  fun at(minute: Int): Instant = Instant.parse("2026-10-16T09:00:00Z").plusSeconds(60L * minute)
}
