package com.sadad.app.feature.contacts

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.R
import com.sadad.app.data.db.Events
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.export.MovementKind
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.TestGraph
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.Commands
import com.sadad.ledger.Direction
import java.util.UUID
import kotlinx.coroutines.flow.first
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
          ContactDetailViewModel(fixture.graph, person.entityId).also {
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
          ContactDetailViewModel(fixture.graph, person.entityId).also {
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
          ContactDetailViewModel(fixture.graph, person.entityId).also {
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
          ContactDetailViewModel(fixture.graph, person.entityId).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      model.state.first { it.loaded }

      model.requestDelete()
      model.state.first { it.confirmDelete }
      model.confirmDelete()
      val undo = model.state.first { it.undoName != null }
      assertEquals("سامي", undo.undoName)
      assertTrue(outboxRows(fixture).isEmpty())

      model.commitDelete(context)
      model.commitDelete(context)

      val done = model.state.first { it.done || it.error != null }
      assertTrue("delete failed: ${done.error}", done.done)
      val rows = outboxRows(fixture)
      assertEquals(1, rows.size)
      assertEquals(CommandType.CONTACT_ARCHIVE, rows.single().type)
      assertEquals(OutboxState.QUEUED, rows.single().state)
    }
  }

  @Test
  fun undoingTheDeleteQueuesNothing() = runTest {
    TestGraph.signedIn().use { fixture ->
      val person = contact("سامي", contactId(1), minute = 0)
      fixture.seedCommands(listOf(person))
      val model =
          ContactDetailViewModel(fixture.graph, person.entityId).also {
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
      val model =
          ContactDetailViewModel(fixture.graph, person.entityId).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      model.state.first { it.loaded }
      model.requestDelete()
      model.state.first { it.confirmDelete }
      model.confirmDelete()
      model.state.first { it.undoName != null }
      online = false

      model.commitDelete(context)

      val state = model.state.first { it.error != null }
      assertEquals(context.getString(R.string.contacts_delete_offline), state.error)
      assertFalse(state.done)
      assertTrue(outboxRows(fixture).isEmpty())
      assertTrue(
          checkNotNull(fixture.graph.current.value).repository.view.value!!.contacts.any {
            it.id == person.entityId
          }
      )
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
          ContactDetailViewModel(fixture.graph, person.entityId).also {
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

  private suspend fun outboxRows(fixture: TestGraph) =
      checkNotNull(fixture.graph.current.value).outbox.snapshot()
}
