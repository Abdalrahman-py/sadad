package com.sadad.app.feature.notifications

import android.content.Intent
import android.net.Uri
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.STORE_B
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.ContactDetail
import com.sadad.app.ui.Home
import com.sadad.ledger.Cents
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReminderNavigationTest {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @After fun close() = scope.cancel()

  private fun reminders(context: android.content.Context): DueReminders =
      DueReminders(File(context.cacheDir, "reminders-${System.nanoTime()}.preferences_pb"), scope)

  private fun reminderIntent(reminder: Reminder): Intent =
      Intent().setData(Uri.parse("sadad://reminder/${Uri.encode(reminder.id)}"))

  @Test
  fun oldExtrasWithoutDataGiveTheStartRoute() = runTest {
    TestGraph.signedIn(STORE_A).use { fixture ->
      assertEquals(
          Home,
          reminderDestination(
              fixture.graph,
              Intent().putExtra("storeUserId", STORE_A).putExtra("contactId", "contact-1"),
              reminders(fixture.context),
          ),
      )
      assertEquals(STORE_A, fixture.graph.current.value!!.account.storeUserId)
    }
  }

  @Test
  fun dataThatNamesAnUnrecordedReminderGivesTheStartRoute() = runTest {
    TestGraph.signedIn(STORE_A).use { fixture ->
      val intent =
          reminderIntent(
              Reminder(
                  STORE_A,
                  "debt-x",
                  "contact-x",
                  "سامي",
                  "متجري",
                  Cents(100),
                  "2026-10-17",
                  "today",
                  1,
              ),
          )
      assertEquals(Home, reminderDestination(fixture.graph, intent, reminders(fixture.context)))
      assertEquals(STORE_A, fixture.graph.current.value!!.account.storeUserId)
    }
  }

  @Test
  fun aRecordedReminderOpenSwitchesToItsStoreAndOpensItsContact() = runTest {
    TestGraph.signedIn(STORE_A).use { fixture ->
      fixture.signIn(STORE_B)
      fixture.activate(STORE_B)
      val reminders = reminders(fixture.context)
      val reminder =
          Reminder(
              STORE_A,
              "debt-1",
              "contact-1",
              "سامي",
              "متجري",
              Cents(1000),
              "2026-10-17",
              "today",
              1,
          )
      assertTrue(reminders.record(reminder))

      val result = reminderDestination(fixture.graph, reminderIntent(reminder), reminders)

      assertEquals(ContactDetail("contact-1"), result)
      assertEquals(STORE_A, fixture.graph.current.value!!.account.storeUserId)
    }
  }

  @Test
  fun extrasThatDisagreeWithTheRecordAreIgnored() = runTest {
    TestGraph.signedIn(STORE_A).use { fixture ->
      fixture.signIn(STORE_B)
      fixture.activate(STORE_B)
      fixture.graph.sessions.switchTo(STORE_A)
      val reminders = reminders(fixture.context)
      val reminder =
          Reminder(
              STORE_A,
              "debt-1",
              "contact-1",
              "سامي",
              "متجري",
              Cents(1000),
              "2026-10-17",
              "today",
              1,
          )
      assertTrue(reminders.record(reminder))
      val intent =
          reminderIntent(reminder).putExtra("storeUserId", STORE_B).putExtra("contactId", "evil")

      val result = reminderDestination(fixture.graph, intent, reminders)

      assertEquals(ContactDetail("contact-1"), result)
      assertEquals(STORE_A, fixture.graph.current.value!!.account.storeUserId)
    }
  }
}
