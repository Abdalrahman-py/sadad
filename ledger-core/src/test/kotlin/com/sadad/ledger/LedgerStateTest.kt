package com.sadad.ledger

import com.sadad.ledger.Events.accepted
import com.sadad.ledger.Events.at
import com.sadad.ledger.Events.origin
import com.sadad.ledger.Events.page
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerStateTest {
  private val fixture = Events.fixtureEvents()

  private fun LedgerState.plain() = view(emptyList(), emptySet(), Fixtures.INSTALLATION_ID, "أحمد")

  // One contact with one receivable debt of 15000, as in operations.json but built locally.
  private val contact = Commands.contactCreate(origin, at(0), "سامي", phone = "+970599000000")
  private val debt =
      Commands.debtCreate(origin, at(1), contact.entityId, Direction.RECEIVABLE, Cents(15000))
  private val payment =
      Commands.paymentCreate(origin, at(2), debt.entityId, Cents(5000), PaymentMethod.CASH)

  @Test
  fun trashPreservesTheWholeHistoryAndFinalDeletionRemovesOnlyThatContact() {
    val full =
        Commands.paymentCreate(origin, at(2), debt.entityId, Cents(15000), PaymentMethod.CASH)
    val other = Commands.contactCreate(origin, at(0), "شخص آخر")
    val archive = Commands.contactArchive(origin, at(3), contact.entityId, 1, "نقل للسلة")
    val restore = Commands.contactRestore(origin, at(4), contact.entityId, 2, "استعادة")
    val again = Commands.contactArchive(origin, at(5), contact.entityId, 3, "نقل للسلة")
    val remove =
        Commands.contactDelete(
            origin,
            at(6),
            contact.entityId,
            4,
            "حذف نهائي",
            entityId = contact.entityId,
        )
    val commands = listOf(contact, other, debt, full, archive, restore, again, remove)
    val events = commands.mapIndexed { i, command -> accepted(command, i + 1L) }
    val trashed = LedgerState.EMPTY.apply(page(events.take(5)))
    assertTrue(trashed.contacts.getValue(contact.entityId).archived)
    assertEquals(1, trashed.debts.size)
    assertEquals(1, trashed.payments.size)
    val restored = trashed.apply(page(events.subList(5, 6)))
    assertTrue(!restored.contacts.getValue(contact.entityId).archived)
    assertEquals(1, restored.plain().payments.size)
    val removed = restored.apply(page(events.drop(6)))
    assertEquals(setOf(other.entityId), removed.contacts.keys)
    assertTrue(removed.debts.isEmpty())
    assertTrue(removed.payments.isEmpty())
    assertTrue(removed.offsets.isEmpty())
  }

  @Test
  fun foldsTheFixtureFlow() {
    val state = LedgerState.EMPTY.apply(page(fixture))
    assertEquals(3L, state.cursor)
    assertEquals(Fixtures.EPOCH, state.epoch)
    assertEquals(Events.STORE_ID, state.storeId)
    assertEquals(1, state.contacts.size)
    assertEquals(1, state.debts.size)
    assertEquals(1, state.payments.size)

    val view = state.plain()
    val row = view.debts.single()
    assertEquals(Cents(15000), row.amount)
    assertEquals(Cents(5000), row.paid)
    assertEquals(Cents(10000), row.confirmedRemaining)
    assertEquals(Cents(10000), row.remaining)
    assertEquals(Cents(10000), view.receivable)
    assertEquals(Cents.ZERO, view.payable)
    assertEquals(1, view.payments.size)
  }

  @Test
  fun pagingGivesTheSameStateAndKeepsTheOldOne() {
    val first = LedgerState.EMPTY.apply(page(fixture.take(2), nextCursor = 2))
    val firstCopy = first.copy()
    val second = first.apply(page(fixture.drop(2), nextCursor = 3))
    assertEquals(LedgerState.EMPTY.apply(page(fixture)), second)
    assertEquals(firstCopy, first)
    assertEquals(2L, first.cursor)
    assertEquals(0, first.payments.size)
  }

  @Test
  fun refusesInconsistentPagesAndKeepsTheReceiver() {
    val state = LedgerState.EMPTY.apply(page(fixture.take(2), nextCursor = 2))
    val before = state.copy()
    val unknownDebt =
        Commands.paymentCreate(
            origin,
            at(5),
            "d0000000-0000-4000-8000-0000000000de",
            Cents(10),
            PaymentMethod.CASH,
        )
    val unknownPayment =
        Commands.paymentReverse(origin, at(5), "d0000000-0000-4000-8000-0000000000df", "خطأ")
    val refused =
        listOf(
            page(fixture.drop(2), schemaVersion = 2),
            page(fixture.drop(2), currency = "USD"),
            page(fixture.drop(2), epoch = "e0000000-0000-4000-8000-000000000000"),
            page(fixture.drop(2), storeId = Events.STORE_ID + 1),
            page(listOf(fixture[1])), // sequence 2 is not above cursor 2
            page(listOf(fixture[2], fixture[2])), // not strictly increasing
            page(listOf(accepted(unknownDebt, 3))),
            page(listOf(accepted(unknownPayment, 3))),
        )
    for (bad in refused) {
      assertThrows(IllegalStateException::class.java) { state.apply(bad) }
      assertEquals(before, state)
    }
    // An empty state takes any store and epoch.
    val other =
        LedgerState.EMPTY.apply(page(fixture, epoch = "e0000000-0000-4000-8000-000000000000"))
    assertEquals("e0000000-0000-4000-8000-000000000000", other.epoch)
    assertNull(LedgerState.EMPTY.epoch)
    assertEquals(0L, LedgerState.EMPTY.cursor)
  }

  @Test
  fun rejectedEventsOnlyAdvanceTheCursor() {
    val state = LedgerState.EMPTY.apply(page(fixture))
    val unknownType =
        JsonObject(
            mapOf(
                "schemaVersion" to JsonPrimitive(1),
                "operationId" to JsonPrimitive("d0000000-0000-4000-8000-000000000001"),
                "entityId" to JsonPrimitive("d0000000-0000-4000-8000-000000000002"),
                "type" to JsonPrimitive("debt.delete"),
                "originInstallationId" to JsonPrimitive(Fixtures.INSTALLATION_ID),
                "originGeneration" to JsonPrimitive(1),
                "payload" to JsonObject(emptyMap()),
            )
        )
    val rejectedPayment =
        Commands.paymentCreate(origin, at(9), fixture[1].entityId, Cents(99999), PaymentMethod.CASH)
    val after =
        state.apply(
            page(
                listOf(
                    Events.rejected(unknownType, 4),
                    Events.rejected(
                        LedgerJson.encodeToJsonElement(LedgerCommand.serializer(), rejectedPayment)
                            as JsonObject,
                        5,
                        "overpayment",
                    ),
                )
            )
        )
    assertEquals(5L, after.cursor)
    assertEquals(state.copy(cursor = 5), after)
  }

  @Test
  fun contactLifecycle() {
    val update =
        Commands.contactUpdate(origin, at(3), contact.entityId, 1, "سامي علي", note = "جار")
    val archive = Commands.contactArchive(origin, at(4), contact.entityId, 2, "انتقل")
    val restore = Commands.contactRestore(origin, at(5), contact.entityId, 3, "عاد")

    val updated = Events.stateOf(contact, debt, payment, update)
    val row = updated.contacts.getValue(contact.entityId)
    assertEquals("سامي علي", row.name)
    assertEquals("", row.phone)
    assertEquals("جار", row.note)
    assertEquals(2L, row.version)

    val archived = Events.stateOf(contact, debt, payment, update, archive)
    val hidden = archived.plain()
    assertTrue(hidden.contacts.isEmpty())
    assertTrue(hidden.debts.isEmpty())
    assertTrue(hidden.payments.isEmpty())
    assertEquals(Cents.ZERO, hidden.receivable)
    assertEquals(Cents.ZERO, hidden.confirmedReceivable)
    assertEquals(listOf(contact.entityId), hidden.archivedContacts.map { it.id })
    assertEquals(3L, hidden.archivedContacts.single().version)

    val restored = Events.stateOf(contact, debt, payment, update, archive, restore).plain()
    assertEquals(listOf(contact.entityId), restored.contacts.map { it.id })
    assertEquals(4L, restored.contacts.single().version)
    assertEquals(Cents(10000), restored.receivable)
    assertEquals(1, restored.payments.size)
    assertTrue(restored.archivedContacts.isEmpty())
  }

  @Test
  fun correctionKeepsPayments() {
    val correct = Commands.debtCorrect(origin, at(3), debt.entityId, 1, Cents(20000), "سعر خاطئ")
    val view = Events.stateOf(contact, debt, payment, correct).plain()
    val row = view.debts.single()
    assertEquals(Cents(20000), row.amount)
    assertEquals(Cents(5000), row.paid)
    assertEquals(Cents(15000), row.confirmedRemaining)
    assertEquals(2L, row.version)
    assertEquals(listOf(payment.entityId), view.payments.map { it.id })
  }

  @Test
  fun reversalStopsCountingButStaysListed() {
    val reverse = Commands.paymentReverse(origin, at(3), payment.entityId, "مكررة")
    val view = Events.stateOf(contact, debt, payment, reverse).plain()
    assertTrue(view.payments.single().reversed)
    val row = view.debts.single()
    assertEquals(Cents.ZERO, row.paid)
    assertEquals(Cents(15000), row.confirmedRemaining)
    assertEquals(Cents(15000), view.receivable)
  }

  @Test
  fun payableDebtsCountAsPayable() {
    val owed = Commands.debtCreate(origin, at(1), contact.entityId, Direction.PAYABLE, Cents(700))
    val view = Events.stateOf(contact, owed).plain()
    assertEquals(Cents(700), view.payable)
    assertEquals(Cents(700), view.confirmedPayable)
    assertEquals(Cents.ZERO, view.receivable)
    assertEquals(Cents.ZERO, view.confirmedReceivable)
  }

  @Test
  fun foldsACommandTimeThatCarriesAnOffset() {
    val shifted = contact.copy(createdAt = "2026-10-16T12:00:00.000+03:00")
    val state = Events.stateOf(shifted)
    assertEquals(Instant.parse("2026-10-16T09:00:00Z"), state.contacts.values.single().createdAt)
  }

  @Test
  fun unreadableEventsAreIllegalStateAndKeepTheReceiver() {
    val state = LedgerState.EMPTY.apply(page(fixture))
    val before = state.copy()
    val badAmount =
        debt.copy(
            entityId = "f0000000-0000-4000-8000-000000000001",
            payload =
                JsonObject(
                    mapOf(
                        "contactId" to JsonPrimitive(fixture[0].entityId),
                        "direction" to JsonPrimitive("receivable"),
                        "amountCents" to JsonPrimitive("abc"),
                    )
                ),
        )
    val badTime =
        debt.copy(entityId = "f0000000-0000-4000-8000-000000000002", createdAt = "not a time")
    for (bad in listOf(badAmount, badTime)) {
      assertThrows(IllegalStateException::class.java) {
        state.apply(page(listOf(accepted(bad, 4))))
      }
      assertEquals(before, state)
    }
  }

  @Test
  fun parsesServerTimesAndKeepsCommandTimes() {
    assertEquals(Instant.parse("2026-10-16T09:00:00.123456Z"), ServerTime.parse(Events.SERVER_TIME))
    assertEquals(Instant.parse("2026-10-16T09:00:00.123456Z"), fixture[0].createdAtInstant())
    assertEquals(
        Instant.parse("2026-10-16T09:00:00.123456Z"),
        fixture[0].result.serverTimeInstant(),
    )
    val state = LedgerState.EMPTY.apply(page(fixture))
    assertEquals(Instant.parse("2026-10-16T09:01:00Z"), state.debts.values.single().createdAt)
    assertEquals(Instant.parse("2026-10-16T09:00:00Z"), state.contacts.values.single().createdAt)
  }

  @Test
  fun replaysAContactKindThroughCreateAndUpdate() {
    val supplier = Commands.contactCreate(origin, at(0), "شركة النور", kind = ContactKind.SUPPLIER)
    val state = Events.stateOf(supplier)
    assertEquals(ContactKind.SUPPLIER, state.contacts.values.single().kind)

    val rename =
        Commands.contactUpdate(
            origin,
            at(1),
            supplier.entityId,
            1,
            "شركة النور",
            kind = ContactKind.CUSTOMER,
        )
    val after = state.apply(page(listOf(accepted(rename, 2))))
    assertEquals(ContactKind.CUSTOMER, after.contacts.values.single().kind)
  }
}
