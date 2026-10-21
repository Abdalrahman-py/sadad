package com.sadad.app.feature.account

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.feature.notifications.Reminder
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import com.sadad.ledger.Cents
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class TopBarActionsTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun theBellShowsUnreadRemindersAndTheAvatarOpensTheSixAccountActions() {
    val fixture = runBlocking { TestGraph.signedIn() }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    try {
      val reminders = DueReminders(File(temporary.root, "reminders.preferences_pb"), scope)
      runBlocking {
        for (id in listOf("first", "second")) {
          reminders.record(
              Reminder(
                  STORE_A,
                  id,
                  "person",
                  "سامي",
                  "متجري",
                  Cents(100),
                  "2026-11-01",
                  "today",
                  1L,
              )
          )
        }
      }
      compose.setContent {
        SadadTheme {
          TopBarActions(
              graph = fixture.graph,
              reminders = reminders,
              onOpenNotifications = {},
              onOpenActivityLog = {},
          )
        }
      }
      compose.waitUntil {
        compose.onAllNodes(androidx.compose.ui.test.hasText("2")).fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithText("2").assertIsDisplayed()
      compose.onNodeWithContentDescription("حسابات المتجر").performClick()
      for (text in
          listOf(
              "تغيير صورة الحساب",
              "المظهر",
              "إعدادات الإشعارات",
              "حسابات المتاجر",
              "مزامنة السجلات",
              "سجل عمليات الدفتر",
          )) {
        compose.onNodeWithText(text).assertIsDisplayed()
      }
    } finally {
      compose.setContent {}
      fixture.close()
      runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
    }
  }
}
