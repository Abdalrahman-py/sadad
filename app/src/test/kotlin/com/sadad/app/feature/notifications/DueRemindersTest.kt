package com.sadad.app.feature.notifications

import com.sadad.ledger.Cents
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// Robolectric: below SDK 26 DataStore replaces its file with File.renameTo, which Windows
// refuses over an existing file, so a second write fails off-device.
@RunWith(RobolectricTestRunner::class)
class DueRemindersTest {
  @get:Rule val temporary = TemporaryFolder()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @After fun close() = scope.cancel()

  @Test
  fun findAnswersTheRecordedReminderAndUnknownIdsHaveNone() = runTest {
    val reminders = DueReminders(File(temporary.root, "reminders.preferences_pb"), scope)
    val event =
        Reminder(
            "store",
            "debt",
            "contact",
            "سامي",
            "المتجر",
            Cents(12000),
            "2026-10-17",
            "today",
            1,
        )
    assertTrue(reminders.record(event))

    assertEquals(event, reminders.find(event.id))
    assertNull(reminders.find("store:debt-x:2026-10-17:today"))
  }

  @Test
  fun sentKeysStayUniqueWhileRemindersMoveBetweenInboxAndTrash() = runTest {
    val reminders = DueReminders(File(temporary.root, "reminders.preferences_pb"), scope)
    val event =
        Reminder(
            "store",
            "debt",
            "contact",
            "سامي",
            "المتجر",
            Cents(12000),
            "2026-10-17",
            "today",
            1,
        )
    assertTrue(reminders.record(event))
    assertFalse(reminders.record(event))
    assertEquals(1, reminders.unreadCount("store").first())
    assertEquals(listOf(event), reminders.inbox("store").first())
    reminders.dismiss(event.id)
    assertTrue(reminders.inbox("store").first().isEmpty())
    assertEquals(event.id, reminders.dismissed("store").first().single().id)
    reminders.restore(event.id)
    assertEquals(event.id, reminders.inbox("store").first().single().id)
    reminders.markAllRead("store")
    assertEquals(0, reminders.unreadCount("store").first())
    assertTrue(reminders.inbox("other-store").first().isEmpty())
    assertFalse(reminders.record(event))
  }
}
