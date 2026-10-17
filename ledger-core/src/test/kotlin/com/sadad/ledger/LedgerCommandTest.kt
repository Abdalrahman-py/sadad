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
              Commands.contactUpdate(origin, at, "c1", expectedVersion = 1, name = "سامي"),
          CommandType.CONTACT_ARCHIVE to
              Commands.contactArchive(origin, at, "c1", expectedVersion = 2, reason = "انتقل"),
          CommandType.CONTACT_RESTORE to
              Commands.contactRestore(origin, at, "c1", expectedVersion = 3, reason = "عاد"),
          CommandType.DEBT_CREATE to
              Commands.debtCreate(origin, at, "c1", Direction.PAYABLE, Cents(100)),
          CommandType.DEBT_CORRECT to
              Commands.debtCorrect(origin, at, "d1", expectedVersion = 1, Cents(90), "خطأ"),
          CommandType.PAYMENT_CREATE to
              Commands.paymentCreate(origin, at, "d1", Cents(10), PaymentMethod.WALLET_PALPAY),
          CommandType.PAYMENT_REVERSE to Commands.paymentReverse(origin, at, "p1", "مكررة"),
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
    assertEquals(JsonObject(mapOf("name" to JsonPrimitive("سامي"))), payloadOf(contact))

    val update =
        Commands.contactUpdate(
            origin,
            at,
            "c1",
            1,
            name = "سامي",
            phone = " +970599000000 ",
            note = " زبون ",
        )
    assertEquals(
        JsonObject(
            mapOf(
                "contactId" to JsonPrimitive("c1"),
                "name" to JsonPrimitive("سامي"),
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
            "c1",
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
                Commands.debtCreate(origin, at, "c1", Direction.RECEIVABLE, Cents(5), dueDate = " ")
            )
    )

    val payment = Commands.paymentCreate(origin, at, "d1", Cents(5), PaymentMethod.CASH, note = "")
    assertFalse("note" in payloadOf(payment))
    assertEquals(
        "خطأ",
        payloadOf(Commands.paymentReverse(origin, at, "p1", "  خطأ ")).text("reason"),
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
            { Commands.debtCreate(origin, at, "c1", Direction.RECEIVABLE, it) },
            { Commands.debtCorrect(origin, at, "d1", 1, it, "تصحيح") },
            { Commands.paymentCreate(origin, at, "d1", it, PaymentMethod.CASH) },
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
      Commands.contactUpdate(origin, at, "c1", 1, name = "")
    }
    assertThrows(IllegalArgumentException::class.java) {
      Commands.contactArchive(origin, at, "c1", 1, reason = " ")
    }
    assertThrows(IllegalArgumentException::class.java) {
      Commands.contactRestore(origin, at, "c1", 1, reason = reason2001)
    }
    assertThrows(IllegalArgumentException::class.java) {
      Commands.debtCorrect(origin, at, "d1", 1, Cents(5), reason = "")
    }
    assertThrows(IllegalArgumentException::class.java) {
      Commands.paymentReverse(origin, at, "p1", reason = reason2001)
    }
    // The limits themselves are allowed; an emoji counts as one character, as in Postgres.
    Commands.contactCreate(origin, at, "ا".repeat(119) + "😀")
    Commands.paymentReverse(origin, at, "p1", "ب".repeat(2000))
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
        setOf("contact.create", "debt.create", "payment.create"),
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
    val command = Commands.contactCreate(origin, at, "سامي", dependsOn = listOf("b", "a", "c"))
    assertEquals(
        JsonArray(listOf("b", "a", "c").map(::JsonPrimitive)),
        encode(command).getValue("dependsOn"),
    )
    assertEquals(
        JsonArray(emptyList()),
        encode(Commands.contactCreate(origin, at, "سامي")).getValue("dependsOn"),
    )
  }

  @Test
  fun envelopeCarriesTheOrigin() {
    val json = encode(Commands.paymentReverse(Origin("i-9", 4, "e-2"), at, "p1", "خطأ"))
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
