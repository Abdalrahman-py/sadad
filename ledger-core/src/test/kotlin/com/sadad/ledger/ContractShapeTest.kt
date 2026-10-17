package com.sadad.ledger

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test

class ContractShapeTest {
  private val schemas =
      Fixtures.readFile("../openapi.json")
          .getValue("components")
          .jsonObject
          .getValue("schemas")
          .jsonObject

  private fun required(name: String): List<String> =
      schemas.getValue(name).jsonObject.getValue("required").jsonArray.map {
        it.jsonPrimitive.content
      }

  private fun elements(serializer: KSerializer<*>): List<String> =
      (0 until serializer.descriptor.elementsCount).map(serializer.descriptor::getElementName)

  @Test
  fun dtosHaveExactlyTheContractFields() {
    assertEquals(required("Changes"), elements(Changes.serializer()))
    assertEquals(required("ChangeEvent"), elements(ChangeEvent.serializer()))
    assertEquals(required("Receipt"), elements(Receipt.serializer()))
  }

  @Test
  fun fixtureReceiptsDecode() {
    val receipts =
        Events.fixtureReceipts().map { LedgerJson.decodeFromJsonElement(Receipt.serializer(), it) }
    assertEquals(listOf(1L, 2L, 3L), receipts.map { it.sequence })
    receipts.forEach {
      assertEquals(ReceiptStatus.ACCEPTED, it.status)
      assertEquals(null, it.code)
    }
  }

  @Test
  fun fixtureChangesDecode() {
    val fixture = Fixtures.read("mobile/changes.json").getValue("response").jsonObject
    val events = Events.fixtureEvents().filter { it.sequence in 2..3 }
    // The fixture keeps only the paging fields; the store, epoch and schema version are test
    // values.
    val response =
        JsonObject(
            fixture +
                mapOf(
                    "schemaVersion" to JsonPrimitive(1),
                    "storeId" to JsonPrimitive(Events.STORE_ID),
                    "epoch" to JsonPrimitive(Fixtures.EPOCH),
                    "events" to
                        JsonArray(
                            events.map {
                              LedgerJson.encodeToJsonElement(ChangeEvent.serializer(), it)
                            }
                        ),
                )
        )
    val changes = LedgerJson.decodeFromJsonElement(Changes.serializer(), response)
    assertEquals(fixture.getValue("cursor").jsonPrimitive.long, changes.cursor)
    assertEquals(fixture.getValue("nextCursor").jsonPrimitive.long, changes.nextCursor)
    assertEquals(false, changes.hasMore)
    assertEquals("ILS", changes.currency)
    assertEquals(
        fixture.getValue("events").jsonArray.map {
          it.jsonObject.getValue("kind").jsonPrimitive.content
        },
        changes.events.map { it.kind },
    )
    assertEquals(events, changes.events)
  }
}
