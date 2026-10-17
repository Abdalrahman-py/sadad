package com.sadad.ledger

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One Ledger command as the api accepts it (`POST /v1/mobile/operations`, checked by
 * `24_apply_one.sql`). Build it with [Commands]; decode server copies with [LedgerJson].
 */
@Serializable
data class LedgerCommand(
    val schemaVersion: Int = 1,
    val operationId: String,
    val entityId: String,
    val type: CommandType,
    val originInstallationId: String,
    val originGeneration: Int,
    val expectedEpoch: String,
    /** ISO-8601 UTC with milliseconds, e.g. `2026-10-16T09:00:00.123Z`. */
    val createdAt: String,
    /** Operation ids that must be accepted first, in order. */
    val dependsOn: List<String> = emptyList(),
    /** The version the entity must still have: contact.update/archive/restore, debt.correct. */
    val expectedVersion: Long? = null,
    val payload: JsonObject,
) {
  /** The payload decoded into the class that belongs to [type]. */
  fun payload(): CommandPayload = LedgerJson.decodeFromJsonElement(type.payloadSerializer, payload)
}

/** Where a command comes from: the installation, its generation and the Ledger epoch it saw. */
data class Origin(val installationId: String, val generation: Int, val epoch: String)

/** The body of `POST /v1/mobile/operations`: 1 to [MAX_BATCH] commands, applied in order. */
@Serializable
data class OperationsRequest(val operations: List<LedgerCommand>) {
  init {
    require(operations.size in 1..MAX_BATCH) {
      "a batch holds 1 to $MAX_BATCH commands, not ${operations.size}"
    }
  }

  companion object {
    /** `MAX_BATCH` in the api's mobile.ts. */
    const val MAX_BATCH = 100
  }
}
