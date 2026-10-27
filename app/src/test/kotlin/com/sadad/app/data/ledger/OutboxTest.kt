package com.sadad.app.data.ledger

import com.sadad.app.data.crypto.Sealed
import com.sadad.app.data.db.Events
import com.sadad.ledger.Commands
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.LedgerJson
import com.sadad.ledger.LedgerState
import com.sadad.ledger.ReceiptStatus
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutboxTest : LedgerTestCase() {

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
    outbox.compact(LedgerState.EMPTY.apply(page))
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
    val archived =
        LedgerState.EMPTY.apply(
            Events.page(listOf(Events.accepted(contact, 1), Events.accepted(archive, 2)))
        )
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

    outbox.compact(archived)
    assertEquals(restore.operationId, outbox.snapshot().single().operationId)

    val unrelated = contactCommand(id = OTHER_CONTACT_ID)
    val earlierPage =
        archived.apply(
            Events.page(
                listOf(Events.accepted(unrelated, 3)),
                cursor = 4,
                nextCursor = 3,
            )
        )
    outbox.compact(earlierPage)
    assertEquals(restore.operationId, outbox.snapshot().single().operationId)

    val restored = earlierPage.apply(Events.page(listOf(Events.accepted(restore, 4))))
    outbox.compact(restored)
    assertTrue(outbox.snapshot().isEmpty())
  }
}
