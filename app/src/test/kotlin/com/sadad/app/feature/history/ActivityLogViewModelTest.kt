package com.sadad.app.feature.history

import androidx.lifecycle.viewModelScope
import com.sadad.app.data.db.Events
import com.sadad.app.data.ledger.FirstDebt
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.data.ledger.receiptOf
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.ChangeEvent
import com.sadad.ledger.CommandType
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import com.sadad.ledger.PaymentMethod
import com.sadad.ledger.Receipt
import com.sadad.ledger.ReceiptStatus
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class ActivityLogViewModelTest {
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun threeFoldedEventsListNewestFirstWithTheirActionsAndTheRefusalText() = runTest {
    TestGraph.signedIn().use { fixture ->
      val repository = checkNotNull(fixture.graph.current.value).repository
      val contact =
          Commands.contactCreate(
              Events.origin,
              at("2026-10-02T09:00:00Z"),
              name = "سامي",
              entityId = UUID.randomUUID().toString(),
          )
      val debt =
          Commands.debtCreate(
              Events.origin,
              at("2026-10-03T09:00:00Z"),
              contactId = contact.entityId,
              direction = Direction.RECEIVABLE,
              amount = Cents(10_000),
              entityId = UUID.randomUUID().toString(),
          )
      val payment =
          Commands.paymentCreate(
              Events.origin,
              at("2026-10-04T09:00:00Z"),
              debtId = debt.entityId,
              amount = Cents(20_000),
              method = PaymentMethod.CASH,
              entityId = UUID.randomUUID().toString(),
          )
      val rejected =
          Events.accepted(payment, 3)
              .copy(
                  kind = ChangeEvent.REJECTED_KIND,
                  result =
                      Receipt(
                          operationId = payment.operationId,
                          entityId = payment.entityId,
                          status = ReceiptStatus.REJECTED,
                          code = "overpayment",
                          sequence = 3,
                          epoch = Events.EPOCH,
                          serverTime = Events.SERVER_TIME,
                      ),
              )
      repository.applyPage(
          Events.page(
              listOf(
                  Events.accepted(contact, 1),
                  Events.accepted(debt, 2),
                  rejected,
              )
          )
      )
      val model = ActivityLogViewModel(fixture.graph)
      fixture.closeWithStore(model.viewModelScope)

      val state = model.state.first { it.loaded && it.entries.size == 3 }

      assertEquals(
          listOf(
              HistoryAction.PAYMENT_CREATE,
              HistoryAction.DEBT_CREATE,
              HistoryAction.CONTACT_CREATE,
          ),
          state.entries.map { it.action },
      )
      assertEquals("الدفعة تتجاوز المتبقي على الدين.", state.entries.first().refused)
      assertEquals(null, state.entries[1].refused)
      // The event carries the server time of the page, not the command's own createdAt.
      assertEquals(
          java.time.OffsetDateTime.parse(Events.SERVER_TIME)
              .toInstant()
              .truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
          state.entries.first().at,
      )
      assertEquals(Events.INSTALLATION_ID, state.entries.first().installationId)
      assertFalse(state.entries.first().mine)
    }
  }

  @Test
  fun aRejectedOutboxRowAppearsInTheGroupAndDismissRemovesIt() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val contactId = store.repository.addContact("سامي", null, null, null, null).getOrThrow()
      fixture.settleAll()
      val debtId =
          store.repository
              .addDebt(contactId, Direction.RECEIVABLE, Cents(10_000), null, null)
              .getOrThrow()
      val row = store.outbox.snapshot().single { it.entityId == debtId }
      store.outbox.settle(
          listOf(receiptOf(row.command, ReceiptStatus.REJECTED, code = "overpayment"))
      )
      val model = ActivityLogViewModel(fixture.graph)
      fixture.closeWithStore(model.viewModelScope)

      val state = model.state.first { it.unaccepted.isNotEmpty() }
      val entry = state.unaccepted.single()
      assertEquals(row.operationId, entry.operationId)
      assertEquals(HistoryAction.DEBT_CREATE, entry.action)
      assertEquals(OutboxState.REJECTED, entry.state)
      assertEquals("الدفعة تتجاوز المتبقي على الدين.", entry.refused)
      assertEquals(null, entry.blockedBy)

      model.dismiss(row.operationId)
      val dismissed = model.state.first { it.unaccepted.isEmpty() }

      assertTrue(dismissed.unaccepted.isEmpty())
      assertTrue(store.outbox.snapshot().none { it.operationId == row.operationId })
    }
  }

  @Test
  fun aCancelledContactWhoseDebtStillExistsCannotBeDismissedFirst() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      store.repository
          .addContact(
              "سامي",
              null,
              null,
              null,
              FirstDebt(Cents(10_000)),
          )
          .getOrThrow()
      val rows = store.outbox.snapshot()
      val contactRow = rows.single { it.type == CommandType.CONTACT_CREATE }
      val debtRow = rows.single { it.type == CommandType.DEBT_CREATE }
      store.outbox.cancel(contactRow.operationId)
      val model = ActivityLogViewModel(fixture.graph)
      fixture.closeWithStore(model.viewModelScope)

      val cancelled = model.state.first { it.unaccepted.size == 2 }
      assertTrue(cancelled.unaccepted.all { it.state == OutboxState.CANCELLED })

      model.dismiss(contactRow.operationId)
      val blocked = model.state.first { state -> state.unaccepted.any { it.blockedBy != null } }

      assertEquals(2, blocked.unaccepted.size)
      assertEquals(
          debtRow.operationId,
          blocked.unaccepted.single { it.operationId == contactRow.operationId }.blockedBy,
      )

      model.dismiss(debtRow.operationId)
      val afterDebt = model.state.first { state -> state.unaccepted.size == 1 }
      assertTrue(afterDebt.unaccepted.single().operationId == contactRow.operationId)
    }
  }

  @Test
  fun anUnsendableRowIsListedAndCanBeDiscarded() = runTest {
    TestGraph.signedIn().use { fixture ->
      val store = checkNotNull(fixture.graph.current.value)
      val contactId = store.repository.addContact("سامي", null, null, null, null).getOrThrow()
      val row = store.outbox.snapshot().single { it.entityId == contactId }
      store.outbox.markUnsendable(listOf(row.operationId))
      val model = ActivityLogViewModel(fixture.graph)
      fixture.closeWithStore(model.viewModelScope)

      val state = model.state.first { it.unaccepted.isNotEmpty() }
      assertEquals(OutboxState.UNSENDABLE, state.unaccepted.single().state)

      model.dismiss(row.operationId)
      val cleared = model.state.first { it.unaccepted.isEmpty() }

      assertTrue(cleared.unaccepted.isEmpty())
      assertTrue(store.outbox.snapshot().none { it.operationId == row.operationId })
    }
  }

  private fun at(text: String): Instant = Instant.parse(text)
}
