package com.sadad.app.feature.trash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.data.ledger.FakeSession
import com.sadad.app.data.ledger.LedgerRepository
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.feature.notifications.Reminder
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.CommandType
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
    return TrashViewModel(scope.repository, reminders, hidden, STORE_A, context)
  }

  private suspend fun TestGraph.rows() = checkNotNull(graph.current.value).outbox.snapshot()

  private suspend fun TestGraph.archiveOne(name: String = "سامي"): String {
    var id: String? = null
    seed { id = addContact(name, "+970599123456", "عميل", null, null).getOrThrow() }
    settleAll()
    seed { archiveContact(id!!, "حذف الزبون من التطبيق").getOrThrow() }
    settleAll()
    repository().view.first { view -> view?.archivedContacts?.any { it.id == id } == true }
    return checkNotNull(id)
  }

  private fun TestGraph.repository() = checkNotNull(graph.current.value).repository

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
  fun restoringAContactQueuesContactRestoreWithItsVersion() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.archiveOne()
      val archived = fixture.repository().view.value!!.archivedContacts.single()
      val model = fixture.model()
      val item = model.state.first { it.items.isNotEmpty() }.items.single()

      model.restore(item)

      val state = model.state.first { it.message != null }
      assertEquals("تمت الاستعادة.", state.message)
      val restore = fixture.rows().single { it.type == CommandType.CONTACT_RESTORE }
      assertEquals(archived.version, restore.command.expectedVersion)
      // The row leaves the list once the server accepts the restore.
      fixture.settleAll()
      assertTrue(model.state.first { it.items.isEmpty() }.items.isEmpty())
    }
  }

  @Test
  fun deleteForGoodHidesTheContactAndQueuesNothing() = runTest {
    TestGraph.signedIn().use { fixture ->
      fixture.archiveOne()
      val before = fixture.rows().size
      val hidden = hidden()
      val reminders = reminders()
      val model = fixture.model(reminders, hidden)
      val item = model.state.first { it.items.isNotEmpty() }.items.single()

      model.deleteForGood(item)

      val state = model.state.first { it.items.isEmpty() }
      assertTrue(state.items.isEmpty())
      assertEquals(before, fixture.rows().size)

      val reopened = fixture.model(reminders, hidden)
      assertTrue(reopened.state.first { it.items.isEmpty() }.items.isEmpty())
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
      val offline = LedgerRepository(scope.store, scope.outbox, session, backgroundScope)
      offline.load()
      offline.view.first { view -> view?.archivedContacts?.isNotEmpty() == true }
      val model = TrashViewModel(offline, reminders(), hidden(), STORE_A, context)
      val item = model.state.first { it.items.isNotEmpty() }.items.single()

      model.restore(item)

      val state = model.state.first { it.message != null }
      assertEquals("تعذرت الاستعادة.", state.message)
      assertTrue(fixture.rows().none { it.type == CommandType.CONTACT_RESTORE })
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
}
