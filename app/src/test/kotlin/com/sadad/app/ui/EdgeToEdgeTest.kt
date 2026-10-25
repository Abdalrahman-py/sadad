package com.sadad.app.ui

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sadad.app.export.MovementKind
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.history.ActivityLogViewModel
import com.sadad.app.feature.history.HistoryScreen
import com.sadad.app.feature.history.HistoryViewModel
import com.sadad.app.feature.home.HomeContent
import com.sadad.app.feature.home.HomeMovement
import com.sadad.app.feature.home.HomeUiState
import com.sadad.app.feature.notifications.DueReminders
import com.sadad.app.feature.notifications.NotificationsScreen
import com.sadad.app.feature.notifications.Reminder
import com.sadad.app.feature.people.ContactsScreen
import com.sadad.app.feature.people.ContactsViewModel
import com.sadad.app.feature.settings.SettingsKit
import com.sadad.app.feature.settings.SettingsScreen
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.theme.SadadTheme
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Target 36 draws edge-to-edge with no opt-out, so a screen that sits under the status or
 * navigation bar hides content or a tap target. Each root destination is rendered in the real
 * `SadadShell` (no top bar: each screen draws its own header), with simulated bar insets, and every
 * tap target on screen must lie between the bars.
 *
 * The device is a Pixel 7 (411 x 914 dp at 420 dpi); Robolectric 4.17 ships no
 * `RobolectricDeviceQualifiers` constant, so the qualifier is spelled out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = "w411dp-h914dp-420dpi")
class EdgeToEdgeTest {
  @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
  @get:Rule val main = MainDispatcherRule()
  @get:Rule val temporary = TemporaryFolder()
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val closeables = mutableListOf<AutoCloseable>()

  @After
  fun close() {
    scope.cancel()
    closeables.forEach(AutoCloseable::close)
  }

  @Test
  fun homeClearsTheBars() =
      inShell(Home) {
        composable<Home> {
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
                          listOf(
                              HomeMovement(
                                  "c1",
                                  "سامي",
                                  Cents(12_000),
                                  MovementKind.PAYMENT,
                                  pending = false,
                                  createdAt = Instant.parse("2026-10-05T10:00:00Z"),
                                  direction = Direction.RECEIVABLE,
                              )
                          ),
                  )
          )
        }
      }

  @Test
  fun contactsClearsTheBars() =
      inShellWithStore(People()) { fixture ->
        awaiting { seedContacts(fixture) }
        val model =
            ContactsViewModel(fixture.graph, SavedStateHandle()).also {
              fixture.closeWithStore(it.viewModelScope)
            }
        awaiting { model.state.first { it.registered > 0 } }
        val destinations: NavGraphBuilder.() -> Unit = {
          composable<People> { ContactsScreen(viewModel = model, onOpenContact = {}) }
        }
        destinations
      }

  @Test
  fun historyClearsTheBars() =
      inShellWithStore(Activity) { fixture ->
        awaiting { seedContacts(fixture) }
        val history = HistoryViewModel(fixture.graph, SavedStateHandle())
        val activity = ActivityLogViewModel(fixture.graph)
        fixture.closeWithStore(history.viewModelScope)
        fixture.closeWithStore(activity.viewModelScope)
        awaiting {
          history.state.first { it.loaded }
          activity.state.first { it.loaded }
        }
        val destinations: NavGraphBuilder.() -> Unit = {
          composable<Activity> {
            HistoryScreen(historyViewModel = history, activityViewModel = activity)
          }
        }
        destinations
      }

  @Test
  fun notificationsClearsTheBars() {
    val reminders = DueReminders(File(temporary.root, "inbox.preferences_pb"), scope)
    awaiting {
      (1..12).forEach {
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
    inShell(Notifications) { composable<Notifications> { NotificationsScreen(reminders, "store") } }
  }

  @Test
  fun settingsClearsTheBars() =
      inShellWithStore(Store) { fixture ->
        val kit = SettingsKit(fixture).also(closeables::add)
        val model = kit.viewModel()
        awaiting { model.state.first { it.loaded } }
        val destinations: NavGraphBuilder.() -> Unit = {
          composable<Store> { SettingsScreen(settingsViewModel = model) }
        }
        destinations
      }

  /**
   * Screens such as the Debt and Payment forms keep their own Scaffold. The shell has already
   * placed its content between the bars, so a nested Scaffold must not add the bar insets again.
   */
  @Test
  fun aNestedScaffoldAddsNoSecondInset() =
      inShell(
          Home,
          check = {
            val outer = compose.onNodeWithTag(OUTER).fetchSemanticsNode().boundsInWindow.top
            val inner = compose.onNodeWithTag(INNER).fetchSemanticsNode().boundsInWindow.top
            assertEquals("nested Scaffold content starts lower than its parent", outer, inner, 1f)
          },
      ) {
        composable<Home> {
          Box(Modifier.fillMaxSize()) {
            Box(Modifier.testTag(OUTER).size(10.dp).align(Alignment.TopStart).clickable {})
            Scaffold(Modifier.fillMaxSize()) { padding ->
              Box(Modifier.padding(padding).testTag(INNER).size(10.dp).clickable {})
            }
          }
        }
      }

  private suspend fun seedContacts(fixture: TestGraph) {
    val repository = checkNotNull(fixture.graph.current.value).repository
    (1..12).forEach { repository.addContact("شخص $it", null, null, null, null).getOrThrow() }
    fixture.settleAll()
  }

  /** Waits on a store or flow without hanging the suite if a regression stops it from finishing. */
  private fun <T> awaiting(block: suspend () -> T): T = runBlocking {
    withTimeout(AWAIT_MS) { block() }
  }

  /** A destination that needs no store of its own. */
  private fun inShell(
      start: Any,
      check: () -> Unit = {},
      destinations: NavGraphBuilder.() -> Unit,
  ) = inShellWithStore(start, check) { destinations }

  /** [arrange] builds the destination's state before composition and returns its graph builder. */
  private fun inShellWithStore(
      start: Any,
      check: () -> Unit = {},
      arrange: (TestGraph) -> NavGraphBuilder.() -> Unit,
  ) {
    awaiting { TestGraph.signedIn() }
        .use { fixture ->
          val destinations = arrange(fixture)
          compose.setContent {
            SadadTheme {
              CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                SadadShell(
                    navController = rememberNavController(),
                    startDestination = start,
                    syncDot = SyncDot.NONE,
                    destinations = destinations,
                )
              }
            }
          }
          applyBars()
          // The destination loads off the main thread; its own tap targets must be there before
          // the check, or the bottom bar alone would satisfy it.
          compose.waitUntil(AWAIT_MS) { tapTargets().size > CHROME_TAP_TARGETS }
          assertEveryTapTargetBetweenTheBars()
          check()
          // Leave the composition before the fixture closes the store under it.
          compose.activityRule.scenario.close()
        }
  }

  private val density: Float
    get() = compose.activity.resources.displayMetrics.density

  private val statusBar: Int
    get() = (STATUS_BAR_DP * density).toInt()

  private val navigationBar: Int
    get() = (NAVIGATION_BAR_DP * density).toInt()

  private fun applyBars() {
    compose.runOnUiThread {
      val insets =
          WindowInsetsCompat.Builder()
              .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBar, 0, 0))
              .setInsets(
                  WindowInsetsCompat.Type.navigationBars(),
                  Insets.of(0, 0, 0, navigationBar),
              )
              .build()
      ViewCompat.dispatchApplyWindowInsets(
          compose.activity.findViewById<View>(android.R.id.content),
          insets,
      )
    }
    compose.waitForIdle()
  }

  private fun tapTargets(): List<Rect> =
      compose
          .onAllNodes(hasClickAction())
          .fetchSemanticsNodes()
          .map { it.boundsInWindow }
          .filter { it.width > 0f && it.height > 0f }

  private fun assertEveryTapTargetBetweenTheBars() {
    val height = compose.onRoot().fetchSemanticsNode().size.height
    val targets = tapTargets()
    assertTrue("the destination shows no tap target of its own", targets.size > CHROME_TAP_TARGETS)
    val covered = targets.filter {
      it.top < statusBar - 0.5f || it.bottom > height - navigationBar + 0.5f
    }
    assertTrue(
        "tap targets under a bar (status $statusBar px, navigation $navigationBar px, screen " +
            "$height px): ${covered.map(Rect::toString)}",
        covered.isEmpty(),
    )
  }

  private companion object {
    const val STATUS_BAR_DP = 32
    const val NAVIGATION_BAR_DP = 24
    const val AWAIT_MS = 10_000L

    /** The five items of the bottom bar. */
    const val CHROME_TAP_TARGETS = 5
    const val OUTER = "outer-top"
    const val INNER = "inner-top"
  }
}
