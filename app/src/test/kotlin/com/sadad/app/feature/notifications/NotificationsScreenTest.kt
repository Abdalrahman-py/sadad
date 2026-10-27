package com.sadad.app.feature.notifications

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import com.sadad.ledger.Cents
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class NotificationsScreenTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val temporary = TemporaryFolder()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @After fun close() = scope.cancel()

  @Test
  fun emptyInboxExplainsThatThereAreNoDueReminders() {
    val reminders = DueReminders(File(temporary.root, "reminders.preferences_pb"), scope)
    compose.setContent { SadadTheme { NotificationsScreen(reminders, "store") } }
    compose.onNodeWithText("لا توجد إشعارات استحقاق حالياً").assertIsDisplayed()
  }

  @Test
  fun markDoneOffersUndoBeforeMovingTheReminderToTrash() {
    val reminders = DueReminders(File(temporary.root, "reminders.preferences_pb"), scope)
    val reminder =
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
    runBlocking { reminders.record(reminder) }
    compose.setContent { SadadTheme { NotificationsScreen(reminders, "store") } }
    // The inbox arrives from DataStore on a coroutine; a 1 s default is enough on a fast machine
    // and not on a loaded CI runner, so wait as long as the undo timer below does.
    compose.waitUntil(10_000) {
      compose.onAllNodesWithText("سامي").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("تحديد الإشعار كمكتمل").performClick()
    compose.onNodeWithText("تم حذف الإشعار ونقله إلى سلة المحذوفات.").assertIsDisplayed()
    compose.onNodeWithText("تراجع").performClick()
    compose.onNodeWithText("سامي").assertIsDisplayed()
    compose.onNodeWithText("تحديد الإشعار كمكتمل").performClick()
    compose.mainClock.advanceTimeBy(6000)
    compose.waitUntil(10000) { runBlocking { reminders.inbox("store").first().isEmpty() } }
  }
}
