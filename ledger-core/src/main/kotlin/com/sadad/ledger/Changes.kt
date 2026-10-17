package com.sadad.ledger

import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** One page of `GET /v1/mobile/changes` (`27_changes.sql`), oldest event first. */
@Serializable
data class Changes(
    val schemaVersion: Int,
    val storeId: Long,
    val epoch: String,
    /** The store's latest sequence. */
    val cursor: Long,
    /** Pass as `after` for the next page. */
    val nextCursor: Long,
    val hasMore: Boolean,
    val currency: String,
    val events: List<ChangeEvent>,
)

/**
 * One applied operation. An accepted event's [kind] is its command type; a rejected one's is
 * [REJECTED_KIND] and it changed nothing. [command] stays raw JSON, because a rejected command may
 * be one this client cannot read.
 */
@Serializable
data class ChangeEvent(
    val sequence: Long,
    val operationId: String,
    val kind: String,
    val entityId: String,
    val userId: String,
    val installationId: String,
    val generation: Int,
    val command: JsonObject,
    val result: Receipt,
    /** Postgres `timestamptz` text; see [createdAtInstant]. */
    val createdAt: String,
) {
  fun createdAtInstant(): Instant = ServerTime.parse(createdAt)

  companion object {
    const val REJECTED_KIND = "operation.rejected"
  }
}

@Serializable
enum class ReceiptStatus {
  @SerialName("accepted") ACCEPTED,
  @SerialName("rejected") REJECTED,
}

/** The server's answer for one command, in a batch response or inside a [ChangeEvent]. */
@Serializable
data class Receipt(
    val operationId: String,
    val entityId: String,
    val status: ReceiptStatus,
    /** The error code of a rejected command; null when accepted. */
    val code: String?,
    val sequence: Long,
    val epoch: String,
    /** Postgres `timestamptz` text; see [serverTimeInstant]. */
    val serverTime: String,
) {
  fun serverTimeInstant(): Instant = ServerTime.parse(serverTime)
}

/** Server times are `timestamptz` text with an offset, e.g. `2026-10-16T09:00:00.123456+00:00`. */
object ServerTime {
  fun parse(text: String): Instant = OffsetDateTime.parse(text).toInstant()
}
