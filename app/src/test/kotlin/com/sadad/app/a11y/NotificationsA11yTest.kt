package com.sadad.app.a11y

import androidx.compose.ui.test.junit4.createComposeRule
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.feature.notifications.NotificationsScreen
import com.sadad.app.feature.notifications.Reminder
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The reminders inbox at both font sizes (ticket 76). */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class NotificationsA11yTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val temporary = TemporaryFolder()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @After fun close() = scope.cancel()

  @Test fun notifications() = notifications(NORMAL_FONT)

  @Test fun notificationsAtLargestFont() = notifications(LARGEST_FONT)

  private fun notifications(fontScale: Float) {
    val reminders = DueReminders(File(temporary.root, "inbox.preferences_pb"), scope)
    awaiting {
      (1..4).forEach {
        reminders.record(
            Reminder(
                "store",
                "debt$it",
                "contact$it",
                "شخص $it",
                "المتجر",
                Cents(1_000L * it),
                "2026-10-17",
                "today",
                it.toLong(),
            )
        )
      }
    }
    compose.showAt(fontScale) { NotificationsScreen(reminders, "store", onBack = {}) }
    compose.assertAccessible()
  }
}
