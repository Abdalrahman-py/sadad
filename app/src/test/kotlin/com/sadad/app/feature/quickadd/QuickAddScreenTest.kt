package com.sadad.app.feature.quickadd

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.R
import com.sadad.app.data.ledger.OutboxRow
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.QuickAdd
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import com.sadad.ledger.ContactKind
import com.sadad.ledger.DebtCreate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The recording flow as the router opens it: `QuickAdd(contactId, payment)` is parsed from the
 * route, not handed to the ViewModel by the test. The constructor entry point had no coverage,
 * which is how a contact's «تسجيل دين» opened an amount step with nobody selected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class QuickAddScreenTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun theRouteFromAContactSelectsThemAndQueuesTheirDebt() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      val contact = seedContact(fixture)
      showRoute(fixture, contact)

      compose.waitUntil(5_000) {
        compose.onAllNodesWithText("خالد").fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithTag(QuickAddTags.AMOUNT).assertIsDisplayed()
      compose.onNodeWithText("سجّل ₪25 على خالد").assertIsDisplayed()

      compose.onNodeWithTag(QuickAddTags.key("2")).performClick()
      compose.onNodeWithTag(QuickAddTags.key("5")).performClick()
      compose.onNodeWithTag(QuickAddTags.SAVE).performClick()

      val payload = runBlocking { awaitRows(fixture, 1) }.single().command.payload() as DebtCreate
      assertEquals(contact, payload.contactId)
    }
  }

  @Test
  fun aRefusalIsVisibleOnTheAmountStep() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      val contact = seedContact(fixture)
      showRoute(fixture, contact)
      compose.waitUntil(5_000) {
        compose.onAllNodesWithText("خالد").fetchSemanticsNodes().isNotEmpty()
      }

      // The store closes between the route opening and the save: commandsFor can build nothing,
      // so save() records the failure. Before the fix the screen never drew it.
      runBlocking { fixture.graph.closeStore() }
      compose.waitForIdle()

      compose.onNodeWithTag(QuickAddTags.key("2")).performClick()
      compose.onNodeWithTag(QuickAddTags.SAVE).performClick()

      compose
          .onNodeWithTag(QuickAddTags.ERROR)
          .assertIsDisplayed()
          .assertTextEquals(context.getString(R.string.quickadd_failed))
    }
  }

  /** The route the shell navigates to, resolved the way `SadadApp` resolves it. */
  private fun showRoute(fixture: TestGraph, contactId: String) {
    compose.setContent {
      SadadTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          val nav = rememberNavController()
          NavHost(navController = nav, startDestination = QuickAdd(contactId, false)) {
            composable<QuickAdd> { entry ->
              val route = entry.toRoute<QuickAdd>()
              val model =
                  remember(route) {
                    QuickAddViewModel(
                        fixture.graph,
                        route.contactId,
                        route.payment,
                        SavedStateHandle(),
                        undoWindowMs = 50,
                    )
                  }
              QuickAddScreen(
                  contactId = route.contactId,
                  payment = route.payment,
                  onClose = {},
                  onOpenContact = {},
                  viewModel = model,
              )
            }
          }
        }
      }
    }
  }

  private fun seedContact(fixture: TestGraph): String = runBlocking {
    checkNotNull(fixture.graph.current.value)
        .repository
        .addContact("خالد", null, null, null, null, ContactKind.CUSTOMER)
        .getOrThrow()
        .also { fixture.settleAll() }
  }

  /** The commit runs off the test thread; poll instead of assuming it landed. */
  private suspend fun awaitRows(fixture: TestGraph, count: Int): List<OutboxRow> {
    val store = checkNotNull(fixture.graph.current.value)
    val deadline = System.currentTimeMillis() + 5_000
    while (store.outbox.snapshot().size < count && System.currentTimeMillis() < deadline) {
      Thread.sleep(10)
    }
    val rows = store.outbox.snapshot()
    assertEquals(count, rows.size)
    return rows
  }
}
