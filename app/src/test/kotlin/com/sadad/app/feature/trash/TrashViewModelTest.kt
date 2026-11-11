package com.sadad.app.feature.trash

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.ledger.FakeSession
import com.sadad.app.data.ledger.LedgerRepository
import com.sadad.app.data.ledger.OutboxState
import com.sadad.app.data.ledger.receiptOf
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.feature.notifications.Reminder
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.testing.seedContactPayment
import com.sadad.app.testing.seedDebt
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
import com.sadad.ledger.ContactView
import com.sadad.ledger.ReceiptStatus
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class TrashViewModelTest {
  @get:Rule val main = MainDispatcherRule()
  @get:Rule val temporary = TemporaryFolder()
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @After fun close() = scope.cancel()

  private fun reminders() = DueReminders(File(temporary.root, "reminders.preferences_pb"), scope)

  private fun hidden() = TrashHidden(File(temporary.root, "trash-$STORE_A.preferences_pb"), scope)

  private fun TestGraph.model(
      reminders: DueReminders = reminders(),
      hidden: TrashHidden = hidden(),
  ): TrashViewModel {
    val scope = checkNotNull(graph.current.value) { "the fixture has no open store" }
    return TrashViewModel(
            scope.repository,
            scope.personLedger.archivedContacts(),
            scope.outbox,
            reminders,
            hidden,
            STORE_A,
            context,
        )
        .also { closeWithStore(it.viewModelScope) }
  }

  private suspend fun TestGraph.rows() = checkNotNull(graph.current.value).outbox.snapshot()

  private suspend fun TestGraph.archiveOne(name: String = "سامي"): String {
    var id: String? = null
    seed { id = addContact(name, "0599123456", "عميل", null, null).getOrThrow() }
    settleAll()
    checkNotNull(graph.current.value)
        .seedDebt(checkNotNull(id), amount = Cents(100), note = "دين محفوظ")
    settleAll()
    checkNotNull(graph.current.value)
        .seedContactPayment(checkNotNull(id), Cents(100), note = "دفعة محفوظة")
    settleAll()
    seed { archiveContact(id!!, "حذف الزبون من التطبيق").getOrThrow() }
    settleAll()
    archivedRows().first { it.id == id }
    return checkNotNull(id)
  }

  /** The trash's people, read from Room a person at a time (ticket 168). */
  private suspend fun TestGraph.archivedRows(): List<ContactView> =
      checkNotNull(graph.current.value).personLedger.archivedContacts().first()

  private fun TestGraph.repository() = checkNotNull(graph.current.value).repository

  @Test
  fun permanentlyDeletingAContactQueuesASharedLedgerDeletionRatherThanHidingItLocally() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.archiveOne()
      val model = fixture.model()
      val item = model.state.first { it.items.isNotEmpty() }.items.single()
      model.askDelete(item)
      model.deleteForGood(item)
      model.state.first { it.confirm == null }
      assertTrue(fixture.rows().any { it.type.wireName == "contact.delete" })
    }
  }

  @Test
  fun anArchivedContactAppearsInTheTrash() = runTest {
    TestGraph.signedIn().use { fixture ->
      val id = fixture.archiveOne()
      val model = fixture.model()

      val state = model.state.first { it.items.isNotEmpty() }

      val item = state.items.single() as TrashItem.Contact
      assertEquals(id, item.contact.id)
      assertEquals("سامي", item.contact.name)
    }
  }

  @Test
  fun theTrashListsTheArchivedContactsOfTheWholeStoreViewInOrder() = runTest {
    TestGraph.signedIn().use { fixture ->
      val first = fixture.archiveOne("سامي")
      val second = fixture.archiveOne("ليلى")
      val whole = fixture.archivedRows()
      assertEquals(listOf(first, second), whole.map { it.id })

      val state = fixture.model().state.first { it.items.size == 2 }

      assertEquals(whole, state.items.map { (it as TrashItem.Contact).contact })
    }
  }

  @Test
  fun restoringAContactQueuesContactRestoreWithItsVersion() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.archiveOne()
      val archived = fixture.archivedRows().single()
      val model = fixture.model()
      val item = model.state.first { it.items.isNotEmpty() }.items.single()

      model.restore(item)
      model.restore(item)

      val state = model.state.first { it.message != null && item.id in it.restoring }
      assertEquals("أُضيف طلب الاستعادة إلى قائمة الانتظار.", state.message)
      val restore = fixture.rows().single { it.type == CommandType.CONTACT_RESTORE }
      assertEquals(archived.version, restore.command.expectedVersion)
      model.restore(item)
      assertEquals(1, fixture.rows().count { it.type == CommandType.CONTACT_RESTORE })
      // The row leaves the list once the server accepts the restore.
      fixture.settleAll()
      assertTrue(model.state.first { it.items.isEmpty() }.items.isEmpty())
    }
  }

  @Test
  fun finalDeletionWaitsForServerConfirmationAndDoesNotReappearAfterReload() = runTest {
    TestGraph.signedIn().use { fixture ->
      val id = fixture.archiveOne()
      val model = fixture.model()
      val item = model.state.first { it.items.isNotEmpty() }.items.single()
      model.askDelete(item)
      model.deleteForGood(item)
      model.state.first { it.confirm == null }
      assertTrue(model.state.value.items.any { it.id == id })
      fixture.settleAll()
      model.state.first { it.items.isEmpty() }
      val database = checkNotNull(fixture.graph.current.value).database
      assertTrue(database.debtDao().all().isEmpty())
      assertTrue(database.paymentDao().all().isEmpty())
      // The person is gone from Room — the only ledger (ticket 168).
      assertTrue(database.contactDao().all().isEmpty())
    }
  }

  @Test
  fun anOfflineRestoreShowsTheRefusalAndQueuesNothing() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.archiveOne()
      val scope = checkNotNull(fixture.graph.current.value)
      val session =
          FakeSession(origin = scope.origin()).apply {
            online = false
            offline = false
          }
      val offlineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      fixture.closeWithStore(offlineScope)
      val offline = LedgerRepository(scope.store, scope.outbox, session, offlineScope)
      offline.load()
      val model =
          TrashViewModel(
              offline,
              scope.personLedger.archivedContacts(),
              scope.outbox,
              reminders(),
              hidden(),
              STORE_A,
              context,
          )
      fixture.closeWithStore(model.viewModelScope)
      val item = model.state.first { it.items.isNotEmpty() }.items.single()

      checkNotNull(model.restore(item)).join()

      val state = model.state.first { it.message != null }
      assertEquals("تعذرت الاستعادة.", state.message)
      assertTrue(fixture.rows().none { it.type == CommandType.CONTACT_RESTORE })
      assertTrue(state.restoring.isEmpty())

      session.online = true
      checkNotNull(model.restore(item)).join()
      model.state.first { item.id in it.restoring }
      assertEquals(1, fixture.rows().count { it.type == CommandType.CONTACT_RESTORE })
    }
  }

  @Test
  fun aDismissedNotificationAppearsAndRestoreReturnsItToTheInbox() = runTest {
    TestGraph.signedIn().use { fixture ->
      val reminders = reminders()
      val event =
          Reminder(
              STORE_A,
              UUID.randomUUID().toString(),
              UUID.randomUUID().toString(),
              "سامي",
              "المتجر",
              Cents(12_000),
              "2026-10-17",
              "today",
              1,
          )
      reminders.record(event)
      reminders.dismiss(event.id)
      val model = fixture.model(reminders = reminders)

      val item =
          model.state.first { it.items.isNotEmpty() }.items.single() as TrashItem.Notification
      assertEquals(event.id, item.reminder.id)

      model.restore(item)

      model.state.first { it.items.isEmpty() && it.message != null }
      assertEquals(event.id, reminders.inbox(STORE_A).first().single().id)
      assertFalse(reminders.dismissed(STORE_A).first().any())
    }
  }

  @Test
  fun reopeningTheTrashKeepsQueuedAndAcceptedRestoresDisabled() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.archiveOne()
      val reminders = reminders()
      val hidden = hidden()
      val model = fixture.model(reminders, hidden)
      val item = model.state.first { it.items.isNotEmpty() }.items.single()
      model.restore(item)
      model.state.first { item.id in it.restoring }

      val reopened = fixture.model(reminders, hidden)
      reopened.state.first { it.items.isNotEmpty() && item.id in it.restoring }
      reopened.restore(item)
      val restore = fixture.rows().single { it.type == CommandType.CONTACT_RESTORE }

      val outbox = checkNotNull(fixture.graph.current.value).outbox
      outbox.settle(listOf(receiptOf(restore.command, ReceiptStatus.ACCEPTED)))
      val awaitingPull = fixture.model(reminders, hidden)
      awaitingPull.state.first { it.items.isNotEmpty() && item.id in it.restoring }
      awaitingPull.restore(item)
      assertEquals(1, fixture.rows().count { it.type == CommandType.CONTACT_RESTORE })
    }
  }

  @Test
  fun aRejectedRestoreCanBeRetried() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.archiveOne()
      val model = fixture.model()
      val item = model.state.first { it.items.isNotEmpty() }.items.single()
      checkNotNull(model.restore(item)).join()
      model.state.first { item.id in it.restoring }
      val restore = fixture.rows().single { it.type == CommandType.CONTACT_RESTORE }
      checkNotNull(fixture.graph.current.value)
          .outbox
          .settle(listOf(receiptOf(restore.command, ReceiptStatus.REJECTED, code = "conflict")))
      model.state.first { it.restoring.isEmpty() }

      checkNotNull(model.restore(item)).join()

      model.state.first { item.id in it.restoring }
      val rows = fixture.rows().filter { it.type == CommandType.CONTACT_RESTORE }
      assertEquals(2, rows.size)
      assertEquals(1, rows.count { it.state == OutboxState.QUEUED })
    }
  }

  @Test
  fun aCancelledRestoreCanBeRetried() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.archiveOne()
      val model = fixture.model()
      val item = model.state.first { it.items.isNotEmpty() }.items.single()
      checkNotNull(model.restore(item)).join()
      model.state.first { item.id in it.restoring }
      val restore = fixture.rows().single { it.type == CommandType.CONTACT_RESTORE }
      checkNotNull(fixture.graph.current.value).outbox.cancel(restore.operationId)
      model.state.first { it.restoring.isEmpty() }

      checkNotNull(model.restore(item)).join()

      model.state.first { item.id in it.restoring }
      val rows = fixture.rows().filter { it.type == CommandType.CONTACT_RESTORE }
      assertEquals(2, rows.size)
      assertEquals(1, rows.count { it.state == OutboxState.QUEUED })
    }
  }
}
