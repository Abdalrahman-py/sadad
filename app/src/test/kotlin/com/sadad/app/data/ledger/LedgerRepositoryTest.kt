package com.sadad.app.data.ledger

import app.cash.turbine.test
import com.sadad.app.data.db.Events
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerCommand
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.ReceiptStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LedgerRepositoryTest : LedgerTestCase() {

  @Test
  fun theViewStaysNullUntilTheRowsAreLoaded() = runTest {
    val repo = repository()

    withContext(Dispatchers.Default) {
      // Real time: by now the outbox and the staff name have emitted, only the rows are missing.
      delay(300)
      assertNull(repo.view.value)

      repo.load()
      withTimeout(5_000) {
        repo.awaitLoaded()
        repo.view.first { it != null }
      }
    }
  }

  @Test
  fun firstContactWithALocalPhoneCannotQueueAnInvalidPersonAndItsDebt() = runTest {
    val repo = repository()
    repo.load()
    for (phone in listOf("0599123456", "599123456", "05991234567")) {
      val result = repo.addContact("أول شخص", phone, null, null, FirstDebt(Cents(25_800)))
      assertTrue(
          "invalid wire phone must be refused before queueing either command",
          result.isFailure,
      )
      assertTrue(outbox.snapshot().isEmpty())
    }
  }

  @Test
  fun compoundSaveShowsTheContactAndTheDebtAsPending() = runTest {
    val repo = repository()
    repo.load()

    repo.view.test {
      val saved =
          repo.addContact(
              name = "سامي",
              phone = "+970599123456",
              category = "family",
              note = "قريب",
              firstDebt = FirstDebt(Cents(10_000)),
          )
      assertTrue(saved.isSuccess)

      val view = awaitView { it.contacts.any { contact -> contact.name == "سامي" } }
      val contact = view.contacts.single { it.name == "سامي" }
      assertTrue(contact.pending)
      val debt = view.debts.single { it.contactId == contact.id }
      assertEquals(Direction.RECEIVABLE, debt.direction)
      assertEquals(Cents(10_000), debt.amount)
      assertTrue(debt.pending)
      assertEquals(Cents(10_000), view.receivable)
      assertEquals(Cents.ZERO, view.confirmedReceivable)
      assertEquals(1, view.pendingContacts)
      assertEquals(1, view.pendingDebts)

      val rows = outbox.snapshot()
      assertEquals(2, rows.size)
      assertTrue(rows.all { it.state == OutboxState.QUEUED })
      val contactRow = rows.single { it.type == CommandType.CONTACT_CREATE }
      val debtRow = rows.single { it.type == CommandType.DEBT_CREATE }
      assertEquals(contact.id, contactRow.entityId)
      assertEquals(listOf(contactRow.operationId), debtRow.command.dependsOn)
      assertEquals(1, session.queued)
    }
  }

  @Test
  fun payContactSplitsTheAmountOldestFirstAndRefusesAnOverpayment() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(
        contactCommand(),
        debtCommand(amount = 10_000, id = DEBT_ID, minute = 1),
        debtCommand(amount = 5_000, id = OTHER_DEBT_ID, minute = 2),
    )

    val paid = repo.payContact(CONTACT_ID, Cents(12_000), PaymentMethod.CASH, "")
    assertTrue(paid.isSuccess)

    val first = outbox.snapshot()
    assertEquals(listOf(10_000L, 2_000L), first.map { paymentAmount(it) })
    assertTrue(first.all { it.type == CommandType.PAYMENT_CREATE })
    assertTrue(first.all { it.command.dependsOn.isEmpty() })
    assertEquals(1, session.queued)

    val overpaid = repo.payContact(CONTACT_ID, Cents(16_000), PaymentMethod.CASH, "")
    assertTrue(overpaid.exceptionOrNull() is Refusal.Invalid)
    assertEquals(first.size, outbox.snapshot().size)
    assertEquals(1, session.queued)
  }

  @Test
  fun pendingParentsAreDependenciesOfTheirChildren() = runTest {
    val repo = repository()
    repo.load()

    val contactId = repo.addContact("سامي", null, null, null, null).getOrThrow()
    val debtId =
        repo.addDebt(contactId, Direction.RECEIVABLE, Cents(5_000), null, null).getOrThrow()
    val payment = repo.payDebt(debtId, Cents(1_000), PaymentMethod.CASH, null)
    assertTrue(payment.isSuccess)

    val rows = outbox.snapshot()
    val contactRow = rows.single { it.type == CommandType.CONTACT_CREATE }
    val debtRow = rows.single { it.type == CommandType.DEBT_CREATE }
    val paymentRow = rows.single { it.type == CommandType.PAYMENT_CREATE }
    assertEquals(listOf(contactRow.operationId), debtRow.command.dependsOn)
    assertEquals(listOf(debtRow.operationId), paymentRow.command.dependsOn)
  }

  @Test
  fun anAcceptedRowStaysPendingUntilThePageHoldsIt() = runTest {
    val repo = repository()
    repo.load()
    repo.addContact("سامي", null, null, null, null)
    val command = outbox.snapshot().single().command

    outbox.settle(listOf(receiptOf(command, ReceiptStatus.ACCEPTED)))
    repo.view.test { assertTrue(awaitView { it.contacts.isNotEmpty() }.contacts.single().pending) }

    repo.applyPage(Events.page(listOf(Events.accepted(command, 1))))

    repo.view.test {
      val view = awaitView { it.contacts.isNotEmpty() && !it.contacts.single().pending }
      assertFalse(view.contacts.single().pending)
    }
    assertTrue(outbox.snapshot().isEmpty())
  }

  @Test
  fun aPageOfAnotherEpochResetsTheStateAndKeepsTheOutbox() = runTest {
    val repo = repository()
    repo.load()
    repo.applyPage(Events.page(listOf(Events.accepted(contactCommand(name = "مؤكد"), 1))))
    repo.addContact("معلق", null, null, null, null)

    val otherEpoch = Events.page(emptyList()).copy(epoch = "11111111-2222-4333-8444-555555555555")
    val failure = runCatching { repo.applyPage(otherEpoch) }.exceptionOrNull()

    assertTrue(failure is LedgerReset)
    assertEquals(1, outbox.snapshot().size)
    repo.view.test {
      val view = awaitView { it.contacts.size == 1 && it.contacts.single().name == "معلق" }
      assertTrue(view.contacts.single().pending)
    }
  }

  @Test
  fun refusingBeforeQueueingWritesNothing() = runTest {
    val repo = repository()
    repo.load()

    session.origin = null
    assertTrue(
        repo.addContact("سامي", null, null, null, null).exceptionOrNull() is Refusal.NotReady
    )
    assertTrue(outbox.snapshot().isEmpty())

    session.origin = Events.origin
    session.online = false
    session.offline = false
    assertTrue(
        repo.addContact("سامي", null, null, null, null).exceptionOrNull()
            is Refusal.OfflineNotAllowed
    )
    assertTrue(outbox.snapshot().isEmpty())
  }

  @Test
  fun archivingWhileOfflineIsOnlineOnly() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(contactCommand())

    session.online = false
    val archived = repo.archiveContact(CONTACT_ID, "سبب")

    assertTrue(archived.exceptionOrNull() is Refusal.OnlineOnly)
    assertTrue(outbox.snapshot().isEmpty())
  }

  @Test
  fun archivingAContactWithAnOpenBalanceIsRefused() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(contactCommand(), debtCommand(amount = 10_000))

    val archived = repo.archiveContact(CONTACT_ID, "سبب")

    assertTrue(archived.exceptionOrNull() is Refusal.Invalid)
    assertTrue(outbox.snapshot().isEmpty())
  }

  @Test
  fun archivingAContactWithAnOpenPayableIsRefused() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(
        contactCommand(),
        debtCommand(amount = 10_000, direction = Direction.PAYABLE),
    )

    val archived = repo.archiveContact(CONTACT_ID, "سبب")

    assertTrue(archived.exceptionOrNull() is Refusal.Invalid)
    assertTrue(outbox.snapshot().isEmpty())
  }

  @Test
  fun aPendingContactCanBeEditedBeforeItIsConfirmed() = runTest {
    val repo = repository()
    repo.load()
    val contactId = repo.addContact("سامي", null, null, null, null).getOrThrow()
    val created = outbox.snapshot().single { it.type == CommandType.CONTACT_CREATE }

    val updated = repo.updateContact(contactId, "سامي الجديد", null, null, null)

    assertTrue(updated.isSuccess)
    val update = outbox.snapshot().single { it.type == CommandType.CONTACT_UPDATE }
    assertEquals(listOf(created.operationId), update.command.dependsOn)
    assertEquals(1L, update.command.expectedVersion)
  }

  @Test
  fun twoEditsBeforeAPullCarryIncreasingVersions() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(contactCommand())

    assertTrue(repo.updateContact(CONTACT_ID, "الاسم الأول", null, null, null).isSuccess)
    assertTrue(repo.updateContact(CONTACT_ID, "الاسم الثاني", null, null, null).isSuccess)

    val versions =
        outbox
            .snapshot()
            .filter { it.type == CommandType.CONTACT_UPDATE }
            .map { it.command.expectedVersion }
    assertEquals(
        "the second edit names the version the first one consumes",
        listOf(1L, 2L),
        versions,
    )
  }

  @Test
  fun cancellingAQueuedCompoundHidesItFromTheView() = runTest {
    val repo = repository()
    repo.load()
    repo.addContact(
        "سامي",
        null,
        null,
        null,
        FirstDebt(Cents(10_000)),
    )
    val contactId = outbox.snapshot().single { it.type == CommandType.CONTACT_CREATE }.operationId

    repo.view.test {
      awaitView { it.contacts.isNotEmpty() && it.debts.isNotEmpty() }
      outbox.cancel(contactId)
      val view = awaitView { it.contacts.isEmpty() && it.debts.isEmpty() }
      assertEquals(0, view.pendingContacts)
      assertEquals(0, view.pendingDebts)
    }
  }

  @Test
  fun anIdOutsideTheViewIsUnknown() = runTest {
    val repo = repository()
    repo.load()

    val added = repo.addDebt(newOperationId(), Direction.RECEIVABLE, Cents(1_000), null, null)
    val paid = repo.payDebt(newOperationId(), Cents(1_000), PaymentMethod.CASH, null)

    assertTrue(added.exceptionOrNull() is Refusal.Unknown)
    assertTrue(paid.exceptionOrNull() is Refusal.Unknown)
    assertTrue(outbox.snapshot().isEmpty())
  }

  @Test
  fun aQueuedEntryKeepsTheConfirmedRowsItAlreadyBuilt() = runTest {
    val repo = repository()
    repo.load()
    repo.seed(contactCommand(), debtCommand())

    repo.view.test {
      val before = awaitView { it.debts.isNotEmpty() }
      repo.payDebt(DEBT_ID, Cents(1_000), PaymentMethod.CASH, null)
      val after = awaitView { it.payments.isNotEmpty() }

      assertEquals(Cents(9_000), after.debts.single().remaining)
      // The same object, not an equal copy: showing the entry rebuilt nothing confirmed.
      assertSame(before.contacts.single(), after.contacts.single())
    }
  }

  private suspend fun LedgerRepository.seed(vararg commands: LedgerCommand) {
    val events = commands.mapIndexed { index, command -> Events.accepted(command, index + 1L) }
    applyPage(Events.page(events))
  }
}
