package com.sadad.app.data.ledger

import app.cash.turbine.test
import com.sadad.app.data.crypto.Sealed
import com.sadad.app.data.db.Events
import com.sadad.app.data.db.LedgerStore
import com.sadad.ledger.Commands
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerJson
import com.sadad.ledger.LedgerState
import com.sadad.ledger.ReceiptStatus
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutboxTest : LedgerTestCase() {

  @Test
  fun acceptedDeletionStaysPendingUntilItsChangesPageRemovesTheContact() = runTest {
    val contact = contactCommand()
    val archive = Commands.contactArchive(Events.origin, Events.at(1), contact.entityId, 1, "سلة")
    // The store's rows live in Room; compact only learns which ids Room holds (ticket 168).
    val store = LedgerStore(db)
    val creation = Events.page(listOf(Events.accepted(contact, 1), Events.accepted(archive, 2)))
    val withContact = LedgerState.EMPTY.apply(creation)
    store.persist(LedgerState.EMPTY, withContact, creation)
    val deletion =
        Commands.contactDelete(
            Events.origin,
            Events.at(2),
            contact.entityId,
            2,
            "نهائي",
            entityId = contact.entityId,
        )
    outbox.append(listOf(deletion))
    outbox.settle(listOf(receiptOf(deletion, ReceiptStatus.ACCEPTED, sequence = 3)))

    outbox.compact(store::present)
    assertEquals(1, outbox.snapshot().size)

    val removal = Events.page(listOf(Events.accepted(deletion, 3)))
    store.persist(withContact, withContact.apply(removal), removal)
    outbox.compact(store::present)
    assertTrue(outbox.snapshot().isEmpty())
  }

  /**
   * Ticket 168: `compact` asks the Room ledger which ids it holds instead of a resident state, and
   * removes exactly the rows today's `compact(state)` removed: one whose entity is in contacts,
   * debts or payments; a `contact.delete` only once the person is gone; a `contact.restore` only
   * once the stored version moved past the one the row was sent with (see the restore test).
   */
  @Test
  fun compactRemovesExactlyTheAcceptedRowsTheRoomLedgerHolds() = runTest {
    val store = LedgerStore(db)
    val creation =
        Events.page(
            listOf(
                Events.accepted(contactCommand(), 1),
                Events.accepted(debtCommand(amount = 10_000, id = DEBT_ID, minute = 1), 2),
                Events.accepted(paymentCommand(minute = 2), 3),
                Events.accepted(
                    contactCommand(name = "ليلى", id = OTHER_CONTACT_ID, minute = 3),
                    4,
                ),
            )
        )
    val state = LedgerState.EMPTY.apply(creation)
    store.persist(LedgerState.EMPTY, state, creation)

    suspend fun acceptedRow(command: LedgerCommand, sequence: Long) {
      outbox.append(listOf(command))
      outbox.settle(listOf(receiptOf(command, ReceiptStatus.ACCEPTED, sequence = sequence)))
    }
    // A person Room does not hold: the row owes its dismissal to a page the store never saw.
    val unknown =
        contactCommand(
            name = "غير مخزّنة",
            id = "f6a7b8c9-d0e1-4f2a-9b3c-4d5e6f708192",
            minute = 4,
        )
    acceptedRow(unknown, 5)
    // The deletion's target is still in Room: the row stays.
    acceptedRow(
        Commands.contactDelete(
            Events.origin,
            Events.at(5),
            OTHER_CONTACT_ID,
            2,
            "نهائي",
            entityId = OTHER_CONTACT_ID,
        ),
        6,
    )
    outbox.compact(store::present)

    // Exactly: every row whose entity Room holds (the seeded contact, debt and payment pages'
    // entities) is gone; the unknown person and the pending deletion stay.
    assertEquals(
        setOf(unknown.entityId, OTHER_CONTACT_ID),
        outbox.snapshot().mapTo(HashSet()) { it.entityId },
    )

    val removal =
        Events.page(
            listOf(
                Events.accepted(
                    Commands.contactArchive(
                        Events.origin,
                        Events.at(5),
                        OTHER_CONTACT_ID,
                        1,
                        "سلة",
                        entityId = OTHER_CONTACT_ID,
                    ),
                    7,
                ),
                Events.accepted(
                    Commands.contactDelete(
                        Events.origin,
                        Events.at(6),
                        OTHER_CONTACT_ID,
                        2,
                        "نهائي",
                        entityId = OTHER_CONTACT_ID,
                    ),
                    8,
                ),
            )
        )
    store.persist(state, state.apply(removal), removal)
    outbox.compact(store::present)
    // The unknown person's row is the only thing left: Room has no person named by it.
    assertEquals(setOf(unknown.entityId), outbox.snapshot().mapTo(HashSet()) { it.entityId })
  }

  @Test
  fun appendSealsThePayloadAndDoesNotContainTheName() = runTest {
    val command = contactCommand(name = "سامي")
    outbox.append(listOf(command))

    val stored = db.outboxDao().all().single()
    assertEquals(command.operationId, stored.operationId)
    assertEquals(command.entityId, stored.entityId)
    assertEquals("contact.create", stored.type)
    assertEquals("queued", stored.state)
    assertEquals(Events.at(0).toEpochMilli(), stored.createdAt)
    assertFalse(String(stored.payload, StandardCharsets.UTF_8).contains("سامي"))

    val opened =
        Sealed.open(key, stored.payload, "outbox:${command.operationId}")
            .toString(StandardCharsets.UTF_8)
    val decoded = LedgerJson.decodeFromString(LedgerCommand.serializer(), opened)
    assertEquals(command, decoded)
  }

  @Test
  fun appendingADuplicateOperationIdLeavesTheOutboxEmpty() = runTest {
    val duplicate = newOperationId()
    val first = contactCommand(operationId = duplicate)
    val second = debtCommand(operationId = duplicate)

    val failure = runCatching { outbox.append(listOf(first, second)) }

    assertTrue(failure.isFailure)
    assertTrue(db.outboxDao().all().isEmpty())
  }

  @Test
  fun queuedReturnsTheNextCommandsInSequenceOrder() = runTest {
    val older = contactCommand(name = "أ")
    val newer = debtCommand()
    outbox.append(listOf(older, newer))

    assertEquals(listOf(older, newer), outbox.queued(10))
    assertEquals(listOf(older), outbox.queued(1))
  }

  @Test
  fun aQueuedRowRefusesDismissalWhileAnUnsendableOneIsDeleted() = runTest {
    val command = contactCommand()
    outbox.append(listOf(command))

    assertEquals(
        DismissResult.NotDismissible(OutboxState.QUEUED),
        outbox.dismiss(command.operationId),
    )
    assertTrue(db.outboxDao().all().any { it.operationId == command.operationId })

    outbox.markUnsendable(listOf(command.operationId))
    assertEquals(OutboxState.UNSENDABLE, outbox.snapshot().single().state)

    assertEquals(DismissResult.Dismissed, outbox.dismiss(command.operationId))
    assertTrue(db.outboxDao().all().isEmpty())
  }

  @Test
  fun settleMarksRowsAndCompactDeletesAcceptedOnesInTheState() = runTest {
    val command = contactCommand()
    outbox.append(listOf(command))

    outbox.settle(listOf(receiptOf(command, ReceiptStatus.ACCEPTED)))
    assertEquals("accepted", db.outboxDao().all().single().state)
    assertTrue(outbox.queued(10).isEmpty())

    val page = Events.page(listOf(Events.accepted(command, 1)))
    val store = LedgerStore(db)
    store.persist(LedgerState.EMPTY, LedgerState.EMPTY.apply(page), page)
    outbox.compact(store::present)
    assertTrue(db.outboxDao().all().isEmpty())
  }

  @Test
  fun cancelCascadesToQueuedDependentsAndDismissWaitsForThem() = runTest {
    val contact = contactCommand()
    val debt = debtCommand().copy(dependsOn = listOf(contact.operationId))
    val payment = paymentCommand().copy(dependsOn = listOf(debt.operationId))
    outbox.append(listOf(contact, debt, payment))

    outbox.cancel(contact.operationId)

    val states = db.outboxDao().all().associate { it.operationId to it.state }
    assertEquals("cancelled", states[contact.operationId])
    assertEquals("cancelled", states[debt.operationId])
    assertEquals("cancelled", states[payment.operationId])
    assertTrue(outbox.snapshot().all { it.state == OutboxState.CANCELLED })

    assertEquals(DismissResult.Blocked(debt.operationId), outbox.dismiss(contact.operationId))
    assertEquals(DismissResult.Dismissed, outbox.dismiss(payment.operationId))
    assertEquals(DismissResult.Dismissed, outbox.dismiss(debt.operationId))
    assertEquals(DismissResult.Dismissed, outbox.dismiss(contact.operationId))
    assertTrue(db.outboxDao().all().isEmpty())
  }

  @Test
  fun rowsExposeTheQueuedCommand() = runTest {
    val command = contactCommand()
    outbox.append(listOf(command))

    val row = outbox.rows.first().single()
    assertEquals(OutboxState.QUEUED, row.state)
    assertEquals(command, row.command)
    assertEquals(command.type, row.type)
  }

  /**
   * Room re-emits after any write to the table, even one that changes no row's words. The flow of
   * [OutboxRow]s must not repeat such an emission: every idle re-emit re-runs the full per-screen
   * projection.
   */
  @Test
  fun aWriteThatLeavesEveryRowEqualDoesNotEmitAgain() = runTest {
    val command = contactCommand()
    outbox.append(listOf(command))

    outbox.rows.test {
      awaitItem()
      // The first stamp is a real change: the row grows a recovery permit.
      db.outboxDao().setRecoveryPermit(listOf(command.operationId), "permit-1")
      awaitItem()
      // The second stamp writes the same words: every row still equals itself.
      db.outboxDao().setRecoveryPermit(listOf(command.operationId), "permit-1")
      // Real time: give the equal emission somewhere to (wrongly) arrive.
      withContext(Dispatchers.Default) { delay(300) }
      expectNoEvents()
    }
  }

  @Test
  fun aLateReceiptDoesNotReviveACancelledRow() = runTest {
    val command = contactCommand()
    outbox.append(listOf(command))
    outbox.cancel(command.operationId)

    outbox.settle(listOf(receiptOf(command, ReceiptStatus.ACCEPTED)))

    assertEquals("cancelled", db.outboxDao().all().single().state)
  }

  @Test
  fun anAcceptedRestoreStaysUntilItsEventIsFolded() = runTest {
    val contact = contactCommand()
    val archive =
        Commands.contactArchive(
            Events.origin,
            Events.at(1),
            contact.entityId,
            1,
            "حذف",
            entityId = contact.entityId,
        )
    val store = LedgerStore(db)
    val creation = Events.page(listOf(Events.accepted(contact, 1), Events.accepted(archive, 2)))
    val archived = LedgerState.EMPTY.apply(creation)
    store.persist(LedgerState.EMPTY, archived, creation)
    val restore =
        Commands.contactRestore(
            Events.origin,
            Events.at(3),
            contact.entityId,
            2,
            "استعادة",
            entityId = contact.entityId,
        )
    outbox.append(listOf(restore))
    outbox.settle(listOf(receiptOf(restore, ReceiptStatus.ACCEPTED, sequence = 4)))

    // The stored contact is still at its archived version, so the row stays.
    outbox.compact(store::present)
    assertEquals(restore.operationId, outbox.snapshot().single().operationId)

    // An unrelated page that does not touch the person or their version changes nothing.
    val unrelated = contactCommand(id = OTHER_CONTACT_ID)
    val earlier = Events.page(listOf(Events.accepted(unrelated, 3)), cursor = 4, nextCursor = 3)
    store.persist(archived, archived.apply(earlier), earlier)
    outbox.compact(store::present)
    assertEquals(restore.operationId, outbox.snapshot().single().operationId)

    // The restore event is folded: the stored version moves past the row's, so the row goes.
    val folded = Events.page(listOf(Events.accepted(restore, 4)))
    store.persist(archived.apply(earlier), archived.apply(earlier).apply(folded), folded)
    outbox.compact(store::present)
    assertTrue(outbox.snapshot().isEmpty())
  }
}
