package com.sadad.app.data.net

import com.sadad.ledger.LedgerJson
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.*

internal const val INSTALLATION_ID = "11111111-1111-4111-8111-111111111111"
internal const val EPOCH = "22222222-2222-4222-8222-222222222222"
internal val SESSION =
    AuthSession("access-token", "refresh-token", Instant.ofEpochSecond(1800000000), "user-a", false)

internal fun fixture(name: String): JsonObject =
    LedgerJson.parseToJsonElement(
            File(System.getProperty("sadad.fixtures"), "mobile/$name.json")
                .readText()
                .replace("{installationId}", INSTALLATION_ID)
                .replace("{epoch}", EPOCH)
        )
        .jsonObject

internal fun receipts(): JsonArray =
    JsonArray(
        fixture("operations").getValue("receipts").jsonArray.map {
          JsonObject(
              it.jsonObject +
                  mapOf(
                      "epoch" to JsonPrimitive(EPOCH),
                      "serverTime" to JsonPrimitive("2026-10-16T09:03:00Z"),
                  )
          )
        }
    )

/** Fill the shared fixture's abbreviated event rows using its original commands and receipts. */
internal fun changes(): JsonObject {
  val commands =
      fixture("operations")
          .getValue("request")
          .jsonObject
          .getValue("operations")
          .jsonArray
          .associateBy { it.jsonObject.getValue("operationId") }
  val results = receipts().associateBy { it.jsonObject.getValue("operationId") }
  val summary = fixture("changes").getValue("response").jsonObject
  val events =
      JsonArray(
          summary.getValue("events").jsonArray.map { event ->
            val stub = event.jsonObject
            val command = commands.getValue(stub.getValue("operationId")).jsonObject
            JsonObject(
                stub +
                    mapOf(
                        "entityId" to command.getValue("entityId"),
                        "userId" to JsonPrimitive("user-a"),
                        "installationId" to JsonPrimitive(INSTALLATION_ID),
                        "generation" to JsonPrimitive(1),
                        "command" to command,
                        "result" to results.getValue(stub.getValue("operationId")),
                        "createdAt" to command.getValue("createdAt"),
                    )
            )
          }
      )
  return JsonObject(
      summary +
          mapOf(
              "schemaVersion" to JsonPrimitive(1),
              "storeId" to JsonPrimitive(7),
              "epoch" to JsonPrimitive(EPOCH),
              "events" to events,
          )
  )
}

internal fun envelope(name: String, value: JsonElement): String = buildJsonObject {
  put("ok", true)
  put(name, value)
}
    .toString()
