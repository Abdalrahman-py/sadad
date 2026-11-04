package com.sadad.ledger

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
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
    assertEquals(required("InstallationName"), elements(InstallationName.serializer()))
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

  @Test
  fun changesReadsInstallations() {
    val response =
        JsonObject(
            mapOf(
                "schemaVersion" to JsonPrimitive(1),
                "storeId" to JsonPrimitive(Events.STORE_ID),
                "epoch" to JsonPrimitive(Fixtures.EPOCH),
                "cursor" to JsonPrimitive(3),
                "nextCursor" to JsonPrimitive(3),
                "hasMore" to JsonPrimitive(false),
                "currency" to JsonPrimitive("ILS"),
                "installations" to
                    JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive(Fixtures.INSTALLATION_ID),
                                    "label" to JsonPrimitive("جهاز الكاشير"),
                                    "staffName" to JsonPrimitive("سامر"),
                                )
                            )
                        )
                    ),
                "events" to JsonArray(emptyList()),
            )
        )
    val changes = LedgerJson.decodeFromJsonElement(Changes.serializer(), response)
    assertEquals(
        listOf(InstallationName(Fixtures.INSTALLATION_ID, "جهاز الكاشير", "سامر")),
        changes.installations,
    )
  }

  @Test
  fun contactCreateCarriesKindOnTheWire() {
    val supplier =
        Commands.contactCreate(
            Events.origin,
            Events.at(0),
            "شركة النور",
            kind = ContactKind.SUPPLIER,
        )
    assertEquals("supplier", supplier.payload.getValue("kind").jsonPrimitive.content)
    val customer = Commands.contactCreate(Events.origin, Events.at(0), "سامي")
    assertEquals("customer", customer.payload.getValue("kind").jsonPrimitive.content)
  }

  @Test
  fun creditAndOffsetWireShapes() {
    val contact = Commands.contactCreate(Events.origin, Events.at(0), "سامي")
    val credit =
        Commands.debtCreate(
            Events.origin,
            Events.at(1),
            contact.entityId,
            Direction.PAYABLE,
            Cents(100),
            debtOrigin = DebtOrigin.OVERPAYMENT,
        )
    assertEquals("overpayment", credit.payload.getValue("origin").jsonPrimitive.content)
    val manual =
        Commands.debtCreate(
            Events.origin,
            Events.at(2),
            contact.entityId,
            Direction.RECEIVABLE,
            Cents(100),
        )
    assertEquals(null, manual.payload["origin"])

    val offset =
        Commands.debtOffset(
            Events.origin,
            Events.at(3),
            manual.entityId,
            credit.entityId,
            Cents(100),
        )
    assertEquals("debt.offset", offset.type.wireName)
    assertEquals(true, offset.type.offline)
    assertEquals(manual.entityId, offset.payload.getValue("receivableDebtId").jsonPrimitive.content)
    assertEquals(credit.entityId, offset.payload.getValue("payableDebtId").jsonPrimitive.content)
    assertEquals(100, offset.payload.getValue("amountCents").jsonPrimitive.int)

    // A zero correction is buildable; the server judges whether the Debt is unused.
    Commands.debtCorrect(Events.origin, Events.at(4), credit.entityId, 1, Cents.ZERO, "عكس رصيد")
  }
}
