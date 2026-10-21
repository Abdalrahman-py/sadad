package com.sadad.app.a11y

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sadad.app.export.MovementKind
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.contacts.ContactsScreen
import com.sadad.app.feature.contacts.ContactsTag
import com.sadad.app.feature.contacts.ContactsViewModel
import com.sadad.app.feature.history.ActivityLogViewModel
import com.sadad.app.feature.history.HistoryScreen
import com.sadad.app.feature.history.HistoryTags
import com.sadad.app.feature.history.HistoryViewModel
import com.sadad.app.feature.home.HomeContent
import com.sadad.app.feature.home.HomeMovement
import com.sadad.app.feature.home.HomeTags
import com.sadad.app.feature.home.HomeUiState
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.feature.notifications.NotificationsScreen
import com.sadad.app.feature.notifications.Reminder
import com.sadad.app.feature.recovery.RecoveryContent
import com.sadad.app.feature.reports.ReportPerson
import com.sadad.app.feature.reports.ReportsContent
import com.sadad.app.feature.reports.ReportsState
import com.sadad.app.feature.reports.ReportsTags
import com.sadad.app.feature.settings.SettingsKit
import com.sadad.app.feature.settings.SettingsScreen
import com.sadad.app.feature.settings.SettingsTags
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import java.io.File
import java.time.Instant
import java.time.YearMonth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The root destinations that need no form input. Each is checked at the normal font size and at the
 * largest the system offers (2.0), on a 360 dp phone (ticket 76).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class ScreensA11yTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()
  @get:Rule val temporary = TemporaryFolder()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val closeables = mutableListOf<AutoCloseable>()

  @After
  fun close() {
    scope.cancel()
    closeables.forEach(AutoCloseable::close)
  }

  @Test fun home() = home(NORMAL_FONT)

  @Test fun homeAtLargestFont() = home(LARGEST_FONT)

  @Test fun contacts() = contacts(NORMAL_FONT)

  @Test fun contactsAtLargestFont() = contacts(LARGEST_FONT)

  @Test fun history() = history(NORMAL_FONT)

  @Test fun historyAtLargestFont() = history(LARGEST_FONT)

  @Test fun reports() = reports(NORMAL_FONT)

  @Test fun reportsAtLargestFont() = reports(LARGEST_FONT)

  @Test fun notifications() = notifications(NORMAL_FONT)

  @Test fun notificationsAtLargestFont() = notifications(LARGEST_FONT)

  @Test fun settings() = settings(NORMAL_FONT)

  @Test fun settingsAtLargestFont() = settings(LARGEST_FONT)

  @Test fun recovery() = recovery(NORMAL_FONT)

  @Test fun recoveryAtLargestFont() = recovery(LARGEST_FONT)

  private fun home(fontScale: Float) {
    compose.showAt(fontScale) {
      HomeContent(
          state =
              HomeUiState(
                  storeName = "متجر النور",
                  month = YearMonth.of(2026, 10),
                  totalReceivable = Cents(123_456),
                  monthDebts = Cents(3_000),
                  monthPayments = Cents(1_000),
                  debtsRatio = 0.75f,
                  paymentsRatio = 0.25f,
                  recent =
                      (1..5).map {
                        HomeMovement(
                            "c$it",
                            "سامي $it",
                            Cents(12_000),
                            MovementKind.PAYMENT,
                            pending = false,
                            createdAt = Instant.parse("2026-10-05T10:00:00Z"),
                            direction = Direction.RECEIVABLE,
                        )
                      },
              )
      )
    }
    compose.assertAccessible(hasTestTag(HomeTags.TOTAL))
  }

  private fun contacts(fontScale: Float) = withStore { fixture ->
    runBlocking { withTimeout(AWAIT_MS) { seedContacts(fixture) } }
    val model =
        ContactsViewModel(fixture.graph, SavedStateHandle()).also {
          fixture.closeWithStore(it.viewModelScope)
        }
    awaiting { model.state.first { it.registered > 0 } }
    compose.showAt(fontScale) { ContactsScreen(viewModel = model, onOpenContact = {}) }
    compose.assertAccessible(hasTestTag(ContactsTag.SEARCH))
  }

  private fun history(fontScale: Float) = withStore { fixture ->
    runBlocking { withTimeout(AWAIT_MS) { seedContacts(fixture) } }
    val history = HistoryViewModel(fixture.graph, SavedStateHandle())
    val activity = ActivityLogViewModel(fixture.graph)
    fixture.closeWithStore(history.viewModelScope)
    fixture.closeWithStore(activity.viewModelScope)
    awaiting {
      history.state.first { it.loaded }
      activity.state.first { it.loaded }
    }
    compose.showAt(fontScale) {
      HistoryScreen(historyViewModel = history, activityViewModel = activity)
    }
    compose.assertAccessible(
        hasTestTag(HistoryTags.TAB_PEOPLE),
        hasTestTag(HistoryTags.TAB_ACTIVITY),
        hasTestTag(HistoryTags.PERIOD_CUSTOM),
    )
  }

  private fun reports(fontScale: Float) {
    compose.showAt(fontScale) {
      ReportsContent(
          state =
              ReportsState(
                  loaded = true,
                  people = (1..8).map { ReportPerson("c$it", "شخص $it", "", Cents(100L * it)) },
                  registered = 8,
              ),
          onPreset = {},
          onCustom = { _, _ -> },
          onQueryChange = {},
          onOpen = {},
          onShare = {},
          onSave = {},
      )
    }
    compose.assertAccessible(hasTestTag(ReportsTags.SAVE))
  }

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
    compose.showAt(fontScale) { NotificationsScreen(reminders, "store") }
    compose.assertAccessible()
  }

  private fun settings(fontScale: Float) = withStore { fixture ->
    val model = SettingsKit(fixture).also(closeables::add).viewModel()
    awaiting { model.state.first { it.loaded } }
    compose.showAt(fontScale) { SettingsScreen(settingsViewModel = model) }
    compose.assertAccessible(hasTestTag(SettingsTags.LIST))
  }

  private fun recovery(fontScale: Float) {
    compose.showAt(fontScale) { RecoveryContent(status = "revoked") }
    compose.assertAccessible(hasText("تسجيل الهاتف كجهاز جديد"))
  }

  private suspend fun seedContacts(fixture: TestGraph) {
    val repository = checkNotNull(fixture.graph.current.value).repository
    (1..8).forEach { repository.addContact("شخص $it", null, null, null, null).getOrThrow() }
    fixture.settleAll()
  }

  private fun <T> awaiting(block: suspend () -> T): T = runBlocking {
    withTimeout(AWAIT_MS) { block() }
  }

  private fun withStore(body: (TestGraph) -> Unit) {
    awaiting { TestGraph.signedIn() }
        .use { fixture ->
          body(fixture)
          // Leave the composition before the fixture closes the store under it.
          compose.waitForIdle()
        }
  }

  private companion object {
    const val AWAIT_MS = 10_000L
  }
}
