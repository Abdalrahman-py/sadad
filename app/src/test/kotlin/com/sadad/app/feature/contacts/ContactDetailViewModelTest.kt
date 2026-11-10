package com.sadad.app.feature.contacts

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.R
import com.sadad.app.contactRecords
import com.sadad.app.data.db.Events
import com.sadad.app.data.ledger.OutboxRow
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.export.MovementKind
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.TestGraph
import com.sadad.app.testing.seedContactPayment
import com.sadad.app.testing.seedPayment
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.Commands
import com.sadad.ledger.DebtCorrect
import com.sadad.ledger.Direction
import com.sadad.ledger.LedgerView
import com.sadad.ledger.PaymentReverse
import java.time.YearMonth
import java.time.ZoneId
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
  fun discardingARejectedDraftRemovesItAndClosesThePage() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val id =
          store.repository
              .addContact(
                  "منى",
                  null,
                  null,
                  null,
                  com.sadad.app.data.ledger.FirstDebt(Cents(4_000)),
              )
              .getOrThrow()
      store.outbox.settle(
          store.outbox.snapshot().map { row ->
            com.sadad.app.data.ledger.receiptOf(
                row.command,
                com.sadad.ledger.ReceiptStatus.REJECTED,
                code =
                    if (row.type == CommandType.CONTACT_CREATE) "contact_limit_reached"
                    else "dependency_rejected",
            )
          }
      )
      val model =
          ContactDetailViewModel(fixture.graph.contactDetailSource(), id, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      model.state.first { it.syncFailure != null }

      model.askDiscard()
      assertTrue(model.state.first { it.confirmDiscard }.confirmDiscard)
      model.confirmDiscard()

      assertTrue(model.state.first { it.done }.done)
      assertTrue(store.outbox.snapshot().isEmpty())
      store.repository.contactRecords.first { it?.failed?.isEmpty() == true }
    }
  }

  @Test
  fun aContactRejectedForTheStoreLimitDoesNotBecomeMissing() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val id =
          store.repository
              .addContact(
                  "إبراهيم",
                  null,
                  "زبون",
                  "ملاحظات محفوظة",
                  com.sadad.app.data.ledger.FirstDebt(Cents(25_800), "دين محفوظ"),
              )
              .getOrThrow()
      val model =
          ContactDetailViewModel(fixture.graph.contactDetailSource(), id, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      model.state.first { it.loaded && !it.missing }
      val rows = store.outbox.snapshot()
      store.outbox.settle(
          rows.map { row ->
            com.sadad.app.data.ledger.receiptOf(
                row.command,
                com.sadad.ledger.ReceiptStatus.REJECTED,
                code =
                    if (row.type == CommandType.CONTACT_CREATE) "contact_limit_reached"
                    else "dependency_rejected",
            )
          }
      )
      val failed = model.state.first { it.missing || it.syncFailure != null }
      assertFalse("the rejected contact must remain as a saved draft", failed.missing)
      assertEquals("إبراهيم", failed.name)
      assertEquals(Cents(25_800), failed.savedDebts.single().amountCents)
      assertEquals(Cents.ZERO, failed.balance)
      assertTrue(failed.movements.isEmpty())
      assertFalse(failed.canDeleteContacts)
      val people =
          com.sadad.app.feature.people.PeopleViewModel(
              fixture.graph.contactRecords(),
              androidx.lifecycle.SavedStateHandle(),
          )
      fixture.closeWithStore(people.viewModelScope)
      val directory =
          people.state.first { it.rows.any { person -> person.id == id && person.syncFailed } }
      assertEquals(Cents.ZERO, directory.rows.single { it.id == id }.net)
      val recreated = ContactDetailViewModel(fixture.graph.contactDetailSource(), id, context)
      fixture.closeWithStore(recreated.viewModelScope)
      assertFalse(recreated.state.first { it.syncFailure != null }.missing)
      val ledger =
          store.repository.view.first {
            it != null && it.contacts.none { person -> person.id == id }
          }!!
      assertEquals(Cents.ZERO, ledger.receivable)
      assertEquals(Cents.ZERO, ledger.confirmedReceivable)
    }
  }

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
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also {
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
  fun theWhatsAppNumberIsTheCodeThenThePhoneWithoutItsZero() = runTest {
    TestGraph.signedIn().use { fixture ->
      val palestine = contact("سامي", contactId(1), phone = "0599123456")
      val israel = contact("هدى", contactId(2), phone = "0599123457", whatsappCode = "972")
      val none = contact("رامي", contactId(3))
      fixture.seedCommands(listOf(palestine, israel, none))

      suspend fun numberOf(id: String): String? {
        val model =
            ContactDetailViewModel(fixture.graph.contactDetailSource(), id, context).also {
              fixture.closeWithStore(it.viewModelScope)
            }
        model.state.first { it.loaded }
        return model.whatsAppNumber()
      }

      assertEquals("970599123456", numberOf(palestine.entityId))
      assertEquals("972599123457", numberOf(israel.entityId))
      assertNull(numberOf(none.entityId))
    }
  }

  @Test
  fun onePaymentSettlingTwoDebtsIsOneMovementAndCanBeReversedTogether() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سامي", contactId(1), minute = 0)
      val first = debt(person.entityId, amount = 10_000, minute = 1)
      val second = debt(person.entityId, amount = 5_000, minute = 2)
      fixture.seedCommands(listOf(person, first, second))
      checkNotNull(fixture.graph.current.value).seedContactPayment(person.entityId, Cents(15_000))
      val model =
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also {
                fixture.closeWithStore(it.viewModelScope)
              }
      val pending = model.state.first { it.loaded && it.balance == Cents.ZERO }
      val queued = pending.movements.single { it.kind == MovementKind.PAYMENT }
      assertTrue(queued.pending)
      assertFalse(queued.canDelete)
      fixture.settleAll()
      val state =
          model.state.first {
            it.loaded && it.balance == Cents.ZERO && it.movements.none { row -> row.pending }
          }
      val payments = state.movements.filter { it.kind == MovementKind.PAYMENT }
      assertEquals("one 150 payment, not separate 100 and 50 rows", 1, payments.size)
      val row = payments.single()
      assertEquals(Cents(15_000), row.amount)
      assertEquals(2, row.parts.size)
      assertTrue(row.canDelete)
      model.deleteConfirmed(row.id)
      val reversals = awaitOutbox(fixture, 2).map { it.command.payload() as PaymentReverse }
      assertEquals(row.parts.toSet(), reversals.map { it.paymentId }.toSet())
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
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also {
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
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also {
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
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also {
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
      val model =
          ContactDetailViewModel(
              fixture.graph.contactDetailSource(),
              person.entityId,
              context,
              undoWindowMs = 50,
          )
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
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also {
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
                    fixture.graph.contactDetailSource(),
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
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also {
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
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also {
                fixture.closeWithStore(it.viewModelScope)
              }
      val state = model.state.first { it.loaded && it.movements.isNotEmpty() }

      assertEquals("the credit Debt is not a row of its own", 2, state.movements.size)
      val row = state.movements.first { it.kind == MovementKind.PAYMENT }
      assertEquals("the row states the payment the owner typed", Cents(50_000), row.amount)
      assertEquals("half of it settled the Debt", Cents(25_000), row.settled)
      assertEquals("the rest is credit", Cents(25_000), row.credit)
      assertTrue("nothing used the credit yet", row.canDelete)
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
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also {
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
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also {
                fixture.closeWithStore(it.viewModelScope)
              }
      val state =
          model.state.first { it.loaded && it.movements.any { it.kind == MovementKind.PAYMENT } }

      model.deleteConfirmed(state.movements.first { it.kind == MovementKind.PAYMENT }.id)

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
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also {
                fixture.closeWithStore(it.viewModelScope)
              }
      val state =
          model.state.first { it.loaded && it.movements.any { it.kind == MovementKind.PAYMENT } }
      val row = state.movements.first { it.kind == MovementKind.PAYMENT }

      assertFalse("part of the credit is spent", row.canDelete)
      assertEquals(
          context.getString(R.string.contacts_delete_credit_used),
          row.blockedReason,
      )

      model.deleteConfirmed(row.id)
      Thread.sleep(300)
      assertTrue(outboxRows(fixture).isEmpty())
    }
  }

  @Test
  fun aCreditCorrectedToZeroLeavesTheHistory() = runTest {
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
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also {
                fixture.closeWithStore(it.viewModelScope)
              }
      val state = model.state.first { it.loaded }

      assertTrue("a zeroed credit is deleted", state.movements.isEmpty())
    }
  }

  @Test
  fun aDebtIsDeletedOnlyAfterTheSecondConfirm() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سمير", contactId(1), minute = 0)
      val open = debt(person.entityId, 10_000, id = contactId(11), minute = 1)
      fixture.seedCommands(listOf(person, open))
      val model =
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also { fixture.closeWithStore(it.viewModelScope) }
      val row = model.state.first { it.movements.isNotEmpty() }.movements.single()
      assertTrue(row.canDelete)

      model.askDeleteMovement(row.id)
      assertFalse(model.state.first { it.deleting != null }.deleteFinal)
      model.confirmDeleteMovement()
      assertTrue(model.state.first { it.deleteFinal }.deleting != null)
      assertTrue("nothing is queued before the final confirm", outboxRows(fixture).isEmpty())

      model.confirmDeleteMovement()
      val correction = awaitOutbox(fixture, 1).single().command.payload() as DebtCorrect
      assertEquals(open.entityId, correction.debtId)
      assertEquals(Cents.ZERO, correction.amountCents)
      val waiting = model.state.first { it.movements.singleOrNull()?.deletePending == true }
      assertEquals(Cents(10_000), waiting.balance)
      assertFalse(waiting.movements.single().canDelete)
      fixture.settleAll()
      val after = model.state.first { it.movements.isEmpty() }
      assertEquals(Cents.ZERO, after.balance)
      assertNull(after.deleting)
    }
  }

  @Test
  fun deletingAThirtyShekelPaymentRestoresItToTheBalanceAfterSync() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سمير", contactId(1), minute = 0)
      val original = debt(person.entityId, 13_000, id = contactId(11), minute = 1)
      val paid = payment(original.entityId, 3_000, id = contactId(12), minute = 2)
      fixture.seedCommands(listOf(person, original, paid))
      val model =
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also { fixture.closeWithStore(it.viewModelScope) }
      val before = model.state.first { it.movements.size == 2 }
      val row = before.movements.first { it.id == paid.entityId }
      assertEquals(Cents(10_000), before.balance)
      assertEquals(13_000L, before.netAfterDeleting(row))
      model.deleteConfirmed(row.id)
      awaitOutbox(fixture, 1)
      fixture.settleAll()
      val after = model.state.first { it.balance == Cents(13_000) }
      assertEquals(listOf(original.entityId), after.movements.map { it.id })
    }
  }

  @Test
  fun cancellingTheDeleteDialogsQueuesNothing() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سمير", contactId(1), minute = 0)
      val open = debt(person.entityId, 10_000, id = contactId(11), minute = 1)
      fixture.seedCommands(listOf(person, open))
      val model =
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also { fixture.closeWithStore(it.viewModelScope) }
      val row = model.state.first { it.movements.isNotEmpty() }.movements.single()

      model.askDeleteMovement(row.id)
      model.confirmDeleteMovement()
      model.dismissDeleteMovement()
      model.confirmDeleteMovement()

      assertNull(model.state.first { it.deleting == null }.deleting)
      assertTrue(outboxRows(fixture).isEmpty())
    }
  }

  @Test
  fun aDebtWithAPaymentCannotBeDeletedUntilThePaymentIsGone() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سمير", contactId(1), minute = 0)
      val open = debt(person.entityId, 10_000, id = contactId(11), minute = 1)
      val paid = payment(open.entityId, 4_000, id = contactId(12), minute = 2)
      fixture.seedCommands(listOf(person, open, paid))
      val model =
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also { fixture.closeWithStore(it.viewModelScope) }
      val rows = model.state.first { it.movements.size == 2 }.movements

      val row = rows.first { it.id == open.entityId }
      assertFalse(row.canDelete)
      assertEquals(context.getString(R.string.contacts_delete_debt_paid), row.blockedReason)
      assertTrue(rows.first { it.id == paid.entityId }.canDelete)
    }
  }

  @Test
  fun thePageStateIsTheSameForEveryPersonAndAnotherPersonsQueuedPaymentStaysOnTheirPage() =
      runTest {
        TestGraph.signedIn().use { fixture ->
          val store = checkNotNull(fixture.graph.current.value)
          val a = contactId(1)
          val b = contactId(2)
          val debtA = debt(a, 10_000, id = contactId(11), minute = 2)
          val debtB = debt(b, 4_000, id = contactId(12), minute = 3)
          val paidB = payment(debtB.entityId, 1_000, id = contactId(13), minute = 4)
          fixture.seedCommands(
              listOf(contact("أ", a, minute = 0), contact("ب", b, minute = 1), debtA, debtB, paidB)
          )
          val queuedPayment = store.seedPayment(debtB.entityId, Cents(2_000))

          val modelA =
              ContactDetailViewModel(fixture.graph.contactDetailSource(), a, context).also {
                fixture.closeWithStore(it.viewModelScope)
              }
          val stateA = modelA.state.first { it.loaded && it.movements.isNotEmpty() }

          // Nothing on A's page changes: their one Debt row, no rows of B.
          assertEquals("أ", stateA.name)
          assertEquals(Cents(10_000), stateA.balance)
          assertEquals(Cents.ZERO, stateA.payable)
          assertEquals(listOf(debtA.entityId), stateA.movements.map { it.id })
          assertFalse(stateA.movements.single().pending)

          val modelB =
              ContactDetailViewModel(fixture.graph.contactDetailSource(), b, context).also {
                fixture.closeWithStore(it.viewModelScope)
              }
          val stateB = modelB.state.first { it.loaded && it.movements.size == 3 }

          // B's page still shows their confirmed payment and the queued one.
          assertEquals(Cents(1_000), stateB.balance)
          assertEquals(
              setOf(debtB.entityId, paidB.entityId, queuedPayment),
              stateB.movements.map { it.id }.toSet(),
          )
          assertTrue(stateB.movements.first { it.id == queuedPayment }.pending)
        }
      }

  @Test
  fun sharingTheStatementIsBuiltFromTheViewThatHoldsOnlyThisPersonsRows() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val a = contactId(1)
      val b = contactId(2)
      val debtA = debt(a, 10_000, id = contactId(11), minute = 2)
      val debtB = debt(b, 4_000, id = contactId(12), minute = 3)
      val paidB = payment(debtB.entityId, 1_000, id = contactId(13), minute = 4)
      fixture.seedCommands(
          listOf(contact("أ", a, minute = 0), contact("ب", b, minute = 1), debtA, debtB, paidB)
      )
      store.seedPayment(debtB.entityId, Cents(2_000))
      val whole =
          store.repository.contactRecords
              .first { it != null && it.ledger.payments.any { row -> row.pending } }!!
              .ledger

      var shared: LedgerView? = null
      val model =
          ContactDetailViewModel(
                  fixture.graph.contactDetailSource(),
                  a,
                  context,
                  shareStatement = { _, _, view, _, _, _ -> shared = view },
              )
              .also { fixture.closeWithStore(it.viewModelScope) }
      model.state.first { it.loaded && it.movements.isNotEmpty() }
      model.share(context)

      val statement = checkNotNull(shared)
      assertEquals(
          "the same ContactView for the person the page shows",
          whole.contacts.first { it.id == a },
          statement.contacts.first { it.id == a },
      )
      assertEquals(
          "their debts as today's whole-store view holds them",
          whole.debts.filter { it.contactId == a },
          statement.debts,
      )
      assertEquals(
          "their payments as today's whole-store view holds them",
          whole.payments.filter { it.contactId == a },
          statement.payments,
      )
      assertEquals(whole.net(a), statement.net(a))
      // B's queued payment is on B's page, not in A's statement.
      assertTrue(whole.payments.any { it.contactId == b && it.pending })
      assertFalse(statement.payments.any { it.contactId != a })
    }
  }

  @Test
  fun theTimelineRowsCarryMonthHeadersAndBalancesAfterInBothOrders() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سامي", contactId(1), minute = 0)
      val octDebt = debt(person.entityId, 10_000, id = contactId(11), minute = 0)
      val octPayment = payment(octDebt.entityId, 4_000, id = contactId(12), minute = 1)
      val novPayable =
          debt(
              person.entityId,
              2_000,
              direction = Direction.PAYABLE,
              id = contactId(13),
              minute = 43_200,
          )
      fixture.seedCommands(listOf(person, octDebt, octPayment, novPayable))

      val model =
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also {
                fixture.closeWithStore(it.viewModelScope)
              }
      val newest = model.state.first { it.loaded && it.movements.size == 3 }

      val octMonth = YearMonth.from(Events.at(0).atZone(ZoneId.systemDefault()))
      val novMonth = YearMonth.from(Events.at(43_200).atZone(ZoneId.systemDefault()))
      val expected =
          mapOf(
              octDebt.entityId to 10_000L,
              octPayment.entityId to 6_000L,
              novPayable.entityId to 4_000L,
          )

      assertEquals(5, newest.timeline.size)
      assertTrue(newest.timeline[0] is ContactTimelineRow.Month)
      assertEquals(novMonth, (newest.timeline[0] as ContactTimelineRow.Month).month)
      assertTrue(newest.timeline[1] is ContactTimelineRow.Movement)
      assertEquals(
          novPayable.entityId,
          (newest.timeline[1] as ContactTimelineRow.Movement).movement.id,
      )
      assertEquals(4_000L, (newest.timeline[1] as ContactTimelineRow.Movement).balanceAfter)
      assertTrue(newest.timeline[2] is ContactTimelineRow.Month)
      assertEquals(octMonth, (newest.timeline[2] as ContactTimelineRow.Month).month)
      assertTrue(newest.timeline[3] is ContactTimelineRow.Movement)
      assertEquals(
          octPayment.entityId,
          (newest.timeline[3] as ContactTimelineRow.Movement).movement.id,
      )
      assertEquals(6_000L, (newest.timeline[3] as ContactTimelineRow.Movement).balanceAfter)
      assertTrue(newest.timeline[4] is ContactTimelineRow.Movement)
      assertEquals(
          octDebt.entityId,
          (newest.timeline[4] as ContactTimelineRow.Movement).movement.id,
      )
      assertEquals(10_000L, (newest.timeline[4] as ContactTimelineRow.Movement).balanceAfter)
      newest.timeline.filterIsInstance<ContactTimelineRow.Movement>().forEach { row ->
        assertEquals(expected[row.movement.id], row.balanceAfter)
      }

      model.toggleOrder()
      val oldest = model.state.first { !it.newestFirst && it.movements.size == 3 }
      assertEquals(5, oldest.timeline.size)
      assertTrue(oldest.timeline[0] is ContactTimelineRow.Month)
      assertEquals(octMonth, (oldest.timeline[0] as ContactTimelineRow.Month).month)
      assertTrue(oldest.timeline[1] is ContactTimelineRow.Movement)
      assertEquals(
          octDebt.entityId,
          (oldest.timeline[1] as ContactTimelineRow.Movement).movement.id,
      )
      assertEquals(10_000L, (oldest.timeline[1] as ContactTimelineRow.Movement).balanceAfter)
      assertTrue(oldest.timeline[2] is ContactTimelineRow.Movement)
      assertEquals(
          octPayment.entityId,
          (oldest.timeline[2] as ContactTimelineRow.Movement).movement.id,
      )
      assertEquals(6_000L, (oldest.timeline[2] as ContactTimelineRow.Movement).balanceAfter)
      assertTrue(oldest.timeline[3] is ContactTimelineRow.Month)
      assertEquals(novMonth, (oldest.timeline[3] as ContactTimelineRow.Month).month)
      assertTrue(oldest.timeline[4] is ContactTimelineRow.Movement)
      assertEquals(
          novPayable.entityId,
          (oldest.timeline[4] as ContactTimelineRow.Movement).movement.id,
      )
      assertEquals(4_000L, (oldest.timeline[4] as ContactTimelineRow.Movement).balanceAfter)
      oldest.timeline.filterIsInstance<ContactTimelineRow.Movement>().forEach { row ->
        assertEquals(expected[row.movement.id], row.balanceAfter)
      }
    }
  }

  @Test
  fun aPayableMovementWithNoPayableBalanceStillShowsTheSupplierLook() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سامي", contactId(1), minute = 0)
      val payableDebt =
          debt(
              person.entityId,
              3_000,
              direction = Direction.PAYABLE,
              id = contactId(11),
              minute = 1,
          )
      val paid = payment(payableDebt.entityId, 3_000, id = contactId(12), minute = 2)
      fixture.seedCommands(listOf(person, payableDebt, paid))

      val model =
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also {
                fixture.closeWithStore(it.viewModelScope)
              }
      val state = model.state.first { it.loaded && it.movements.size == 2 }

      assertEquals(Cents.ZERO, state.payable)
      assertTrue(state.isSupplier)
    }
  }

  private fun ContactDetailViewModel.deleteConfirmed(movementId: String) {
    askDeleteMovement(movementId)
    confirmDeleteMovement()
    confirmDeleteMovement()
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
