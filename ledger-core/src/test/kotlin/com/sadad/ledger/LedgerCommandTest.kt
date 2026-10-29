package com.sadad.ledger

import java.time.Instant
import java.util.TimeZone
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerCommandTest {
  private val origin = Origin(Fixtures.INSTALLATION_ID, generation = 1, epoch = Fixtures.EPOCH)
  private val at = Instant.parse("2026-10-16T09:00:00Z")
  private val contactId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
  private val debtId = "b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e"
  private val paymentId = "c3d4e5f6-a7b8-4c9d-8e1f-2a3b4c5d6e7f"
  private val fixture = Fixtures.read("mobile/operations.json")
  private val fixtureCommands =
      fixture.getValue("request").jsonObject.getValue("operations").jsonArray

  private fun encode(command: LedgerCommand): JsonObject =
      LedgerJson.encodeToJsonElement(LedgerCommand.serializer(), command).jsonObject

  private fun payloadOf(command: LedgerCommand) = encode(command).getValue("payload").jsonObject

  private fun fixtureCommand(index: Int) = fixtureCommands[index].jsonObject

  private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

  /** The three operations.json commands, built with Commands from the fixture's values. */
  private fun builtFixtureCommands(): List<LedgerCommand> {
    val (contact, debt, payment) = (0..2).map(::fixtureCommand)
    return listOf(
        Commands.contactCreate(
            origin,
            Instant.parse(contact.text("createdAt")),
            name = "أبو خالد",
            phone = "+970599123456",
            entityId = contact.text("entityId"),
            operationId = contact.text("operationId"),
        ),
        Commands.debtCreate(
            origin,
            Instant.parse(debt.text("createdAt")),
            contactId = contact.text("entityId"),
            direction = Direction.RECEIVABLE,
            amount = Cents(15000),
            note = "بضاعة",
            entityId = debt.text("entityId"),
            operationId = debt.text("operationId"),
            dependsOn = listOf(contact.text("operationId")),
        ),
        Commands.paymentCreate(
            origin,
            Instant.parse(payment.text("createdAt")),
            debtId = debt.text("entityId"),
            amount = Cents(5000),
            method = PaymentMethod.CASH,
            entityId = payment.text("entityId"),
            operationId = payment.text("operationId"),
            dependsOn = listOf(debt.text("operationId")),
        ),
    )
  }

  /** One command of each type, built with blank or padded optional text. */
  private fun oneOfEach(): Map<CommandType, LedgerCommand> =
      mapOf(
          CommandType.CONTACT_CREATE to Commands.contactCreate(origin, at, name = "سامي"),
          CommandType.CONTACT_UPDATE to
              Commands.contactUpdate(origin, at, contactId, expectedVersion = 1, name = "سامي"),
          CommandType.CONTACT_ARCHIVE to
              Commands.contactArchive(origin, at, contactId, expectedVersion = 2, reason = "انتقل"),
          CommandType.CONTACT_RESTORE to
              Commands.contactRestore(origin, at, contactId, expectedVersion = 3, reason = "عاد"),
          CommandType.DEBT_CREATE to
              Commands.debtCreate(origin, at, contactId, Direction.PAYABLE, Cents(100)),
          CommandType.DEBT_CORRECT to
              Commands.debtCorrect(origin, at, debtId, expectedVersion = 1, Cents(90), "خطأ"),
          CommandType.PAYMENT_CREATE to
              Commands.paymentCreate(origin, at, debtId, Cents(10), PaymentMethod.WALLET_PALPAY),
          CommandType.PAYMENT_REVERSE to Commands.paymentReverse(origin, at, paymentId, "مكررة"),
      )

  @Test
  fun buildsTheFixtureCommandsExactly() {
    val built = builtFixtureCommands()
    built.forEachIndexed { index, command -> assertEquals(fixtureCommand(index), encode(command)) }
    val request =
        LedgerJson.encodeToJsonElement(OperationsRequest.serializer(), OperationsRequest(built))
    assertEquals(fixture.getValue("request"), request)
  }

  @Test
  fun fixtureCommandsRoundTripThroughTypedPayloads() {
    val expected =
        listOf(
            ContactCreate(name = "أبو خالد", phone = "+970599123456"),
            DebtCreate(
                contactId = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d",
                direction = Direction.RECEIVABLE,
                amountCents = Cents(15000),
                note = "بضاعة",
            ),
            PaymentCreate(
                debtId = "b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e",
                amountCents = Cents(5000),
                method = PaymentMethod.CASH,
            ),
        )
    fixtureCommands.forEachIndexed { index, element ->
      val command = LedgerJson.decodeFromJsonElement(LedgerCommand.serializer(), element)
      assertEquals(expected[index], command.payload())
      assertEquals(element, encode(command))
    }
  }

  @Test
  fun blankOptionalTextIsOmittedAndTextIsTrimmed() {
    val contact =
        Commands.contactCreate(
            origin,
            at,
            name = "  سامي  ",
            phone = "   ",
            category = "",
            note = " \t ",
        )
    assertEquals(
        JsonObject(mapOf("name" to JsonPrimitive("سامي"), "kind" to JsonPrimitive("customer"))),
        payloadOf(contact),
    )

    val update =
        Commands.contactUpdate(
            origin,
            at,
            contactId,
            1,
            name = "سامي",
            phone = " +970599000000 ",
            note = " زبون ",
        )
    assertEquals(
        JsonObject(
            mapOf(
                "contactId" to JsonPrimitive(contactId),
                "name" to JsonPrimitive("سامي"),
                "kind" to JsonPrimitive("customer"),
                "phone" to JsonPrimitive("+970599000000"),
                "note" to JsonPrimitive("زبون"),
            )
        ),
        payloadOf(update),
    )

    val debt =
        Commands.debtCreate(
            origin,
            at,
            contactId,
            Direction.RECEIVABLE,
            Cents(5),
            note = "  ",
            dueDate = " 2026-11-01 ",
        )
    val payload = payloadOf(debt)
    assertFalse("note" in payload)
    assertEquals("2026-11-01", payload.text("dueDate"))
    assertFalse(
        "dueDate" in
            payloadOf(
                Commands.debtCreate(
                    origin,
                    at,
                    contactId,
                    Direction.RECEIVABLE,
                    Cents(5),
                    dueDate = " ",
                )
            )
    )

    val payment =
        Commands.paymentCreate(origin, at, debtId, Cents(5), PaymentMethod.CASH, note = "")
    assertFalse("note" in payloadOf(payment))
    assertEquals(
        "خطأ",
        payloadOf(Commands.paymentReverse(origin, at, paymentId, "  خطأ ")).text("reason"),
    )
  }

  @Test
  fun expectedVersionIsTopLevelOnlyForVersionedTypes() {
    val versioned =
        setOf(
            CommandType.CONTACT_UPDATE,
            CommandType.CONTACT_ARCHIVE,
            CommandType.CONTACT_RESTORE,
            CommandType.DEBT_CORRECT,
        )
    val expectedVersions =
        mapOf(
            CommandType.CONTACT_UPDATE to 1L,
            CommandType.CONTACT_ARCHIVE to 2L,
            CommandType.CONTACT_RESTORE to 3L,
            CommandType.DEBT_CORRECT to 1L,
        )
    for ((type, command) in oneOfEach()) {
      val json = encode(command)
      assertEquals(type, command.type)
      assertFalse("$type payload", "expectedVersion" in json.getValue("payload").jsonObject)
      if (type in versioned) {
        assertEquals(
            "$type",
            expectedVersions.getValue(type),
            json.getValue("expectedVersion").jsonPrimitive.long,
        )
      } else {
        assertFalse("$type", "expectedVersion" in json)
      }
    }
  }

  @Test
  fun moneyMustBeWithinTheWireRange() {
    val builders: List<(Cents) -> LedgerCommand> =
        listOf(
            { Commands.debtCreate(origin, at, contactId, Direction.RECEIVABLE, it) },
            { Commands.paymentCreate(origin, at, debtId, it, PaymentMethod.CASH) },
        )
    for (build in builders) {
      for (bad in listOf(Cents(0), Cents(-1), Cents.MAX_WIRE + Cents(1))) {
        assertThrows(IllegalArgumentException::class.java) { build(bad) }
      }
      for (good in listOf(Cents(1), Cents.MAX_WIRE)) {
        val amount = payloadOf(build(good)).getValue("amountCents").jsonPrimitive
        assertFalse(amount.isString)
        assertEquals(good.value, amount.long)
      }
    }
    // A correction may zero an unused Debt, but never goes negative or above the wire range.
    for (bad in listOf(Cents(-1), Cents.MAX_WIRE + Cents(1))) {
      assertThrows(IllegalArgumentException::class.java) {
        Commands.debtCorrect(origin, at, debtId, 1, bad, "تصحيح")
      }
    }
    for (good in listOf(Cents(0), Cents(1), Cents.MAX_WIRE)) {
      Commands.debtCorrect(origin, at, debtId, 1, good, "تصحيح")
    }
  }

  @Test
  fun requiredTextIsChecked() {
    val name121 = "ا".repeat(121)
    val reason2001 = "ب".repeat(2001)
    assertThrows(IllegalArgumentException::class.java) { Commands.contactCreate(origin, at, "   ") }
    assertThrows(IllegalArgumentException::class.java) {
      Commands.contactCreate(origin, at, name121)
    }
    assertThrows(IllegalArgumentException::class.java) {
      Commands.contactUpdate(origin, at, contactId, 1, name = "")
    }
    assertThrows(IllegalArgumentException::class.java) {
      Commands.contactArchive(origin, at, contactId, 1, reason = " ")
    }
    assertThrows(IllegalArgumentException::class.java) {
      Commands.contactRestore(origin, at, contactId, 1, reason = reason2001)
    }
    assertThrows(IllegalArgumentException::class.java) {
      Commands.debtCorrect(origin, at, debtId, 1, Cents(5), reason = "")
    }
    assertThrows(IllegalArgumentException::class.java) {
      Commands.paymentReverse(origin, at, paymentId, reason = reason2001)
    }
    // The limits themselves are allowed; an emoji counts as one character, as in Postgres.
    Commands.contactCreate(origin, at, "ا".repeat(119) + "😀")
    Commands.paymentReverse(origin, at, paymentId, "ب".repeat(2000))
  }

  @Test
  fun idsMustBeLowercaseUuids() {
    val goodId = "0f8e7d6c-5b4a-4392-8170-6f5e4d3c2b1a"
    val payloadIds: Map<String, (String) -> LedgerCommand> =
        mapOf(
            "contactUpdate" to { id -> Commands.contactUpdate(origin, at, id, 1, "سامي") },
            "contactArchive" to { id -> Commands.contactArchive(origin, at, id, 1, "انتقل") },
            "contactRestore" to { id -> Commands.contactRestore(origin, at, id, 1, "عاد") },
            "debtCreate" to
                { id ->
                  Commands.debtCreate(origin, at, id, Direction.RECEIVABLE, Cents(1))
                },
            "debtCorrect" to { id -> Commands.debtCorrect(origin, at, id, 1, Cents(1), "خطأ") },
            "paymentCreate" to
                { id ->
                  Commands.paymentCreate(origin, at, id, Cents(1), PaymentMethod.CASH)
                },
            "paymentReverse" to { id -> Commands.paymentReverse(origin, at, id, "مكررة") },
        )
    for ((name, build) in payloadIds) {
      for (bad in listOf("x", "A1B2C3D4-E5F6-4A7B-8C9D-0E1F2A3B4C5D")) {
        assertThrows("$name $bad", IllegalArgumentException::class.java) { build(bad) }
      }
      build(goodId) // A lowercase canonical UUID passes.
    }
    for (bad in listOf("x", "A1B2C3D4-E5F6-4A7B-8C9D-0E1F2A3B4C5D")) {
      assertThrows(IllegalArgumentException::class.java) {
        Commands.contactCreate(origin, at, "سامي", entityId = bad)
      }
      assertThrows(IllegalArgumentException::class.java) {
        Commands.contactCreate(origin, at, "سامي", operationId = bad)
      }
      assertThrows(IllegalArgumentException::class.java) {
        Commands.contactCreate(origin, at, "سامي", dependsOn = listOf(bad))
      }
    }
    Commands.contactCreate(
        origin,
        at,
        "سامي",
        entityId = goodId,
        operationId = contactId,
        dependsOn = listOf(paymentId),
    )
  }

  @Test
  fun vocabularyMatchesTheServer() {
    fun <T> names(values: Array<T>, serializer: (T) -> String) = values.map(serializer).toSet()
    assertEquals(
        setOf(
            "contact.create",
            "contact.update",
            "contact.archive",
            "contact.restore",
            "debt.create",
            "debt.offset",
            "debt.correct",
            "payment.create",
            "payment.reverse",
        ),
        names(CommandType.entries.toTypedArray()) { serialName(CommandType.serializer(), it) },
    )
    assertEquals(
        setOf("receivable", "payable"),
        names(Direction.entries.toTypedArray()) { serialName(Direction.serializer(), it) },
    )
    assertEquals(
        setOf("cash", "bank", "wallet", "wallet_jawwal", "wallet_palpay"),
        names(PaymentMethod.entries.toTypedArray()) { serialName(PaymentMethod.serializer(), it) },
    )
    assertEquals(
        setOf("contact.create", "debt.create", "debt.offset", "payment.create"),
        CommandType.entries
            .filter { it.offline }
            .map { serialName(CommandType.serializer(), it) }
            .toSet(),
    )
  }

  private fun <T> serialName(serializer: kotlinx.serialization.KSerializer<T>, value: T): String =
      LedgerJson.encodeToJsonElement(serializer, value).jsonPrimitive.content

  @Test
  fun freshIdsAreDistinctUuids() {
    val first = Commands.contactCreate(origin, at, "سامي")
    val second = Commands.contactCreate(origin, at, "سامي")
    val ids = listOf(first.operationId, first.entityId, second.operationId, second.entityId)
    ids.forEach { UUID.fromString(it) }
    assertEquals(4, ids.toSet().size)
    assertNotEquals(first.operationId, first.entityId)
  }

  @Test
  fun createdAtIsUtcMillisecondsWhateverTheDeviceZone() {
    val saved = TimeZone.getDefault()
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Gaza"))
      val command = Commands.contactCreate(origin, Instant.ofEpochMilli(1_761_000_000_123), "سامي")
      assertEquals("2025-10-20T22:40:00.123Z", encode(command).text("createdAt"))
      val micros = Instant.parse("2026-10-16T09:00:00.123456Z")
      assertEquals(
          "2026-10-16T09:00:00.123Z",
          encode(Commands.contactCreate(origin, micros, "سامي")).text("createdAt"),
      )
    } finally {
      TimeZone.setDefault(saved)
    }
  }

  @Test
  fun dependsOnKeepsOrderAndIsAnEmptyArrayByDefault() {
    val dependencies = listOf(paymentId, contactId, debtId)
    val command = Commands.contactCreate(origin, at, "سامي", dependsOn = dependencies)
    assertEquals(
        JsonArray(dependencies.map(::JsonPrimitive)),
        encode(command).getValue("dependsOn"),
    )
    assertEquals(
        JsonArray(emptyList()),
        encode(Commands.contactCreate(origin, at, "سامي")).getValue("dependsOn"),
    )
  }

  @Test
  fun envelopeCarriesTheOrigin() {
    val json = encode(Commands.paymentReverse(Origin("i-9", 4, "e-2"), at, paymentId, "خطأ"))
    assertEquals(1L, json.getValue("schemaVersion").jsonPrimitive.long)
    assertEquals("i-9", json.text("originInstallationId"))
    assertEquals(4L, json.getValue("originGeneration").jsonPrimitive.long)
    assertEquals("e-2", json.text("expectedEpoch"))
    assertEquals("payment.reverse", json.text("type"))
  }

  @Test
  fun batchHoldsOneToOneHundredCommands() {
    val command = Commands.contactCreate(origin, at, "سامي")
    OperationsRequest(List(100) { command })
    assertThrows(IllegalArgumentException::class.java) { OperationsRequest(List(101) { command }) }
    assertThrows(IllegalArgumentException::class.java) { OperationsRequest(emptyList()) }
  }

  private val payloadClasses =
      mapOf(
          CommandType.CONTACT_CREATE to ContactCreate::class,
          CommandType.CONTACT_UPDATE to ContactUpdate::class,
          CommandType.CONTACT_ARCHIVE to ContactArchive::class,
          CommandType.CONTACT_RESTORE to ContactRestore::class,
          CommandType.DEBT_CREATE to DebtCreate::class,
          CommandType.DEBT_CORRECT to DebtCorrect::class,
          CommandType.PAYMENT_CREATE to PaymentCreate::class,
          CommandType.PAYMENT_REVERSE to PaymentReverse::class,
      )

  @Test
  fun payloadDecodesByType() {
    for ((type, command) in oneOfEach()) {
      val decoded = LedgerJson.decodeFromJsonElement(LedgerCommand.serializer(), encode(command))
      assertEquals(command, decoded)
      assertEquals(type, decoded.type)
      assertTrue("$type", payloadClasses.getValue(type).isInstance(decoded.payload()))
    }
  }
}
