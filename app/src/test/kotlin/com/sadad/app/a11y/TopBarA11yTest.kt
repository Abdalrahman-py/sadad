package com.sadad.app.a11y

import androidx.compose.ui.test.junit4.createComposeRule
import com.sadad.app.feature.account.TopBarActions
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The avatar and bell sit above every root destination, so they get the same checks. */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class TopBarA11yTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()
  @get:Rule val temporary = TemporaryFolder()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @After fun close() = scope.cancel()

  @Test fun topBar() = topBar(NORMAL_FONT)

  @Test fun topBarAtLargestFont() = topBar(LARGEST_FONT)

  private fun topBar(fontScale: Float) {
    runBlocking { withTimeout(10_000) { TestGraph.signedIn() } }
        .use { fixture ->
          val reminders = DueReminders(File(temporary.root, "bell.preferences_pb"), scope)
          compose.showAt(fontScale) {
            TopBarActions(
                onOpenNotifications = {},
                onOpenActivityLog = {},
                graph = fixture.graph,
                reminders = reminders,
            )
          }
          compose.assertAccessible()
        }
  }
}
