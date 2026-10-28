package com.sadad.app.feature.contacts

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.R
import com.sadad.app.data.db.Events
import com.sadad.app.data.ledger.OutboxRow
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.export.MovementKind
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.TestGraph
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.Commands
import com.sadad.ledger.DebtCorrect
import com.sadad.ledger.Direction
import com.sadad.ledger.PaymentReverse
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContactDetailViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun theBalanceIsTheSumOfTheRemainingReceivableDebts() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سامي", contactId(1), minute = 0)
      val first = debt(person.entityId, amount = 10_000, minute = 1)
      val second = debt(person.entityId, amount = 4_000, minute = 2)
      val paid = payment(first.entityId, amount = 2_500, minute = 3)
      val payable = debt(person.entityId, amount = 3_000, direction = Direction.PAYABLE, minute = 4)
      fixture.seedCommands(listOf(person, first, second, paid, payable))

      val model =
          ContactDetailViewModel(fixture.graph, person.entityId, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      val state = model.state.first { it.loaded && it.movements.size == 4 }

      assertEquals(Cents(11_500), state.balance)
      assertEquals(Cents(3_000), state.payable)
      assertEquals("سامي", state.name)
      assertEquals(Direction.PAYABLE, state.movements.first { it.id == payable.entityId }.direction)
      assertEquals(Direction.RECEIVABLE, state.movements.first { it.id == paid.entityId }.direction)
    }
  }

  @Test
  fun reversedPaymentsAreNotListedInTheHistory() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سامي", contactId(1), minute = 0)
      val open = debt(person.entityId, amount = 10_000, minute = 1)
      val paid = payment(open.entityId, amount = 4_000, minute = 2)
      val reversed =
          Commands.paymentReverse(
              Events.origin,
              Events.at(3),
              paid.entityId,
              "خطأ",
              entityId = UUID.randomUUID().toString(),
              operationId = UUID.randomUUID().toString(),
          )
      fixture.seedCommands(listOf(person, open, paid, reversed))
      fixture.graph.current.value!!.repository.view.first { view ->
        view != null && view.payments.any { it.reversed }
      }

      val model =
          ContactDetailViewModel(fixture.graph, person.entityId, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      val state = model.state.first { it.loaded }

      assertEquals(Cents(10_000), state.balance)
      assertEquals(1, state.movements.size)
      assertEquals(MovementKind.DEBT, state.movements.single().kind)
    }
  }

  @Test
  fun deletingAContactWithAnOpenBalanceAsksToSettleFirstAndQueuesNothing() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سامي", contactId(1), minute = 0)
      fixture.seedCommands(listOf(person, debt(person.entityId, amount = 10_000, minute = 1)))
      val model =
          ContactDetailViewModel(fixture.graph, person.entityId, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      model.state.first { it.loaded && it.balance == Cents(10_000) }

      model.requestDelete()

      val state = model.state.first { it.settleFirst != null }
      assertEquals(Cents(10_000), state.settleFirst)
      assertTrue(outboxRows(fixture).isEmpty())
    }
  }

  @Test
  fun deletingASettledContactWithoutUndoQueuesOneArchive() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سامي", contactId(1), minute = 0)
      fixture.seedCommands(listOf(person))
      val model =
          ContactDetailViewModel(fixture.graph, person.entityId, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      model.state.first { it.loaded }

      model.requestDelete()
      model.state.first { it.confirmDelete }
      model.confirmDelete()
      val undo = model.state.first { it.undoName != null }
      assertEquals("سامي", undo.undoName)
      assertTrue(outboxRows(fixture).isEmpty())

      model.commitDelete()
      model.commitDelete()

      val done = model.state.first { it.done || it.error != null }
      assertTrue("delete failed: ${done.error}", done.done)
      val rows = outboxRows(fixture)
      assertEquals(1, rows.size)
      assertEquals(CommandType.CONTACT_ARCHIVE, rows.single().type)
      assertEquals(OutboxState.QUEUED, rows.single().state)
    }
  }

  @Test
  fun theConfirmedDeleteStillQueuesWhenTheScreenIsGone() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سامي", contactId(1), minute = 0)
      fixture.seedCommands(listOf(person))
      val model = ContactDetailViewModel(fixture.graph, person.entityId, context, undoWindowMs = 50)
      model.state.first { it.loaded }
      model.requestDelete()
      model.state.first { it.confirmDelete }
      model.confirmDelete()
      model.state.first { it.undoName != null }

      // The user leaves the Contact: the screen and the ViewModel scope are gone, and the undo
      // window must still end in the archive the confirm dialog promised.
      model.viewModelScope.cancel()
      Thread.sleep(500)

      val rows = outboxRows(fixture)
      assertEquals(1, rows.size)
      assertEquals(CommandType.CONTACT_ARCHIVE, rows.single().type)
    }
  }

  @Test
  fun undoingTheDeleteQueuesNothing() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سامي", contactId(1), minute = 0)
      fixture.seedCommands(listOf(person))
      val model =
          ContactDetailViewModel(fixture.graph, person.entityId, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      model.state.first { it.loaded }

      model.requestDelete()
      model.state.first { it.confirmDelete }
      model.confirmDelete()
      model.state.first { it.undoName != null }

      model.undoDelete()

      val state = model.state.first { it.undoName == null }
      assertNull(state.undoName)
      assertTrue(outboxRows(fixture).isEmpty())
    }
  }

  @Test
  fun anOfflineArchiveShowsTheRefusalAndQueuesNothing() = runTest {
    var online = true
    TestGraph.signedIn(online = { online }).use { fixture ->
      val person = contact("سامي", contactId(1))
      fixture.seedCommands(listOf(person))
      // The refusal is produced by a job this test starts, so the test holds the scope and joins
      // that job instead of waiting for the state it happens to write. Waiting on the state alone
      // left the job running past the assertion, and under CI load that raced the fixture closing
      // the database underneath it (#168).
      val undoScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
      try {
        val model =
            ContactDetailViewModel(
                    fixture.graph,
                    person.entityId,
                    context,
                    undoScope = undoScope,
                )
                .also { fixture.closeWithStore(it.viewModelScope) }
        model.state.first { it.loaded }
        model.requestDelete()
        model.state.first { it.confirmDelete }
        model.confirmDelete()
        model.state.first { it.undoName != null }
        online = false

        model.commitDelete()
        undoScope.coroutineContext.job.children.toList().joinAll()

        val state = model.state.first { it.error != null }
        assertEquals(context.getString(R.string.contacts_delete_offline), state.error)
        assertFalse(state.done)
        assertTrue(outboxRows(fixture).isEmpty())
        assertTrue(
            checkNotNull(fixture.graph.current.value).repository.view.value!!.contacts.any {
              it.id == person.entityId
            }
        )
      } finally {
        undoScope.cancel()
      }
    }
  }

  @Test
  fun aPayableBalanceAlsoPreventsArchiving() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سامي", contactId(1))
      fixture.seedCommands(
          listOf(person, debt(person.entityId, 1000, direction = Direction.PAYABLE))
      )
      val model =
          ContactDetailViewModel(fixture.graph, person.entityId, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      model.state.first { it.loaded && it.payable == Cents(1000) }
      model.requestDelete()
      val state = model.state.first { it.settleFirst != null }
      assertEquals(Cents(1000), state.settleFirst)
      assertFalse(state.confirmDelete)
      assertTrue(outboxRows(fixture).isEmpty())
    }
  }

  @Test
  fun anOverpaymentIsOnePaymentRowCarryingWhatSettledAndWhatBecameCredit() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سمير", contactId(1), minute = 0)
      val owed = debt(person.entityId, amount = 25_000, minute = 1)
      // One save: the payment that settles the Debt, and the credit Debt for the excess, at the
      // same instant — that is how the quick-add flow writes a compound.
      val paid = payment(owed.entityId, amount = 25_000, minute = 2)
      val credit = creditDebt(person.entityId, amount = 25_000, minute = 2)
      fixture.seedCommands(listOf(person, owed, paid, credit))

      val model =
          ContactDetailViewModel(fixture.graph, person.entityId, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      val state = model.state.first { it.loaded && it.movements.isNotEmpty() }

      assertEquals("the credit Debt is not a row of its own", 2, state.movements.size)
      val row = state.movements.first { it.kind == MovementKind.PAYMENT }
      assertEquals("the row states the payment the owner typed", Cents(50_000), row.amount)
      assertEquals("half of it settled the Debt", Cents(25_000), row.settled)
      assertEquals("the rest is credit", Cents(25_000), row.credit)
      assertTrue("nothing used the credit yet", row.canReverse)
    }
  }

  @Test
  fun aDebtPaidFromCreditSaysHowMuchWasDeducted() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سمير", contactId(1), minute = 0)
      val credit = creditDebt(person.entityId, amount = 25_000, minute = 1)
      val later = debt(person.entityId, amount = 10_000, minute = 2)
      fixture.seedCommands(
          listOf(person, credit, later, offset(later.entityId, credit.entityId, 10_000, minute = 2))
      )

      val model =
          ContactDetailViewModel(fixture.graph, person.entityId, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      val state = model.state.first { it.loaded && it.movements.isNotEmpty() }

      val row = state.movements.first { it.id == later.entityId }
      assertEquals(Cents(10_000), row.offsetted)
    }
  }

  @Test
  fun reversingAnOverpayingPaymentQueuesTheReversalAndTheZeroCorrection() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سمير", contactId(1), minute = 0)
      val owed = debt(person.entityId, amount = 25_000, minute = 1)
      val paid = payment(owed.entityId, amount = 25_000, minute = 2)
      val credit = creditDebt(person.entityId, amount = 25_000, minute = 2)
      fixture.seedCommands(listOf(person, owed, paid, credit))

      val model =
          ContactDetailViewModel(fixture.graph, person.entityId, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      val state =
          model.state.first { it.loaded && it.movements.any { it.kind == MovementKind.PAYMENT } }

      model.reverse(state.movements.first { it.kind == MovementKind.PAYMENT }.id)

      val rows = awaitOutbox(fixture, 2)
      assertEquals(
          listOf(CommandType.PAYMENT_REVERSE, CommandType.DEBT_CORRECT),
          rows.map { it.type },
      )
      assertEquals(paid.entityId, (rows[0].command.payload() as PaymentReverse).paymentId)
      val correction = rows[1].command.payload() as DebtCorrect
      assertEquals(credit.entityId, correction.debtId)
      assertEquals(Cents.ZERO, correction.amountCents)
    }
  }

  @Test
  fun reversingIsRefusedOncePartOfTheCreditWasUsed() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سمير", contactId(1), minute = 0)
      val owed = debt(person.entityId, amount = 25_000, minute = 1)
      val paid = payment(owed.entityId, amount = 25_000, minute = 2)
      val credit = creditDebt(person.entityId, amount = 25_000, minute = 2)
      val later = debt(person.entityId, amount = 10_000, minute = 3)
      fixture.seedCommands(
          listOf(
              person,
              owed,
              paid,
              credit,
              later,
              offset(later.entityId, credit.entityId, 10_000, minute = 3),
          )
      )

      val model =
          ContactDetailViewModel(fixture.graph, person.entityId, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      val state =
          model.state.first { it.loaded && it.movements.any { it.kind == MovementKind.PAYMENT } }
      val row = state.movements.first { it.kind == MovementKind.PAYMENT }

      assertFalse("part of the credit is spent", row.canReverse)
      assertEquals(
          context.getString(R.string.contacts_reverse_credit_used),
          row.blockedReason,
      )

      model.reverse(row.id)
      Thread.sleep(300)
      assertTrue(outboxRows(fixture).isEmpty())
    }
  }

  @Test
  fun aCreditCorrectedToZeroIsShownStruckThrough() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سمير", contactId(1), minute = 0)
      val credit = creditDebt(person.entityId, amount = 25_000, minute = 1)
      val zeroed =
          Commands.debtCorrect(
              Events.origin,
              Events.at(2),
              credit.entityId,
              expectedVersion = 1,
              amount = Cents.ZERO,
              reason = "الدفعة عُكست",
              entityId = UUID.randomUUID().toString(),
              operationId = UUID.randomUUID().toString(),
          )
      fixture.seedCommands(listOf(person, credit, zeroed))

      val model =
          ContactDetailViewModel(fixture.graph, person.entityId, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      val state = model.state.first { it.loaded && it.movements.isNotEmpty() }

      val row = state.movements.first { it.id == credit.entityId }
      assertTrue("a zeroed credit stays in the history, struck through", row.struck)
      assertEquals(Cents.ZERO, row.amount)
    }
  }

  private suspend fun outboxRows(fixture: TestGraph) =
      checkNotNull(fixture.graph.current.value).outbox.snapshot()

  private suspend fun awaitOutbox(fixture: TestGraph, count: Int): List<OutboxRow> {
    val deadline = System.currentTimeMillis() + 5_000
    while (outboxRows(fixture).size < count && System.currentTimeMillis() < deadline) {
      Thread.sleep(10)
    }
    return outboxRows(fixture)
  }
}
