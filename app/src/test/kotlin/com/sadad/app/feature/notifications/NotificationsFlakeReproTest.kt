package com.sadad.app.feature.notifications

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import com.sadad.ledger.Cents
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class NotificationsFlakeReproTest(@Suppress("unused") private val round: Int) {
  companion object {
    @JvmStatic
    @ParameterizedRobolectricTestRunner.Parameters
    fun rounds(): List<Array<Any>> = (1..1500).map { arrayOf<Any>(it) }
  }

  private val compose = createComposeRule()
  private val temporary = TemporaryFolder()
  @get:Rule val rules: RuleChain = RuleChain.outerRule(temporary).around(compose)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  @After fun close() = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

  @Test
  fun row() {
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
    try {
      compose.waitUntil(3_000) {
        compose.onAllNodesWithText("سامي").fetchSemanticsNodes().isNotEmpty()
      }
    } catch (e: Throwable) {
      val stored = runBlocking { withTimeoutOrNull(2000) { reminders.inbox("store").first() } }
      val tree = runCatching { compose.onRoot().printToString() }.getOrElse { it.toString() }
      val threads =
          Thread.getAllStackTraces().entries.joinToString("\n") { (t, s) ->
            t.name + " " + t.state + " " + s.take(12).joinToString(" < ")
          }
      throw AssertionError("round=$round stored=$stored\nTREE\n$tree\nTHREADS\n$threads", e)
    }
  }
}
