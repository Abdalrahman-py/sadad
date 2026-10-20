package com.sadad.app.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import com.sadad.app.export.MovementKind
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import java.time.Clock
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class HomeScreenTest {
  @get:Rule val compose = createComposeRule()

  private val state =
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
                  ),
                  HomeMovement(
                      "c2",
                      "ليلى",
                      Cents(5_000),
                      MovementKind.DEBT,
                      pending = true,
                      createdAt = Instant.parse("2026-10-04T10:00:00Z"),
                  ),
              ),
      )

  private fun show(onOpenContact: (String) -> Unit = {}) {
    compose.setContent {
      SadadTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          HomeContent(state = state, onOpenContact = onOpenContact)
        }
      }
    }
  }

  @Test
  fun greetsTheStoreAndShowsTheTotal() {
    show()

    compose.onNodeWithText("مرحبًا بك، متجر النور").assertIsDisplayed()
    compose.onNodeWithText("1,234.56 ₪").assertIsDisplayed()
  }

  @Test
  fun anEmptyStoreNameFallsBackToMyStore() {
    compose.setContent { SadadTheme { HomeContent(state = state.copy(storeName = "")) } }

    compose.onNodeWithText("مرحبًا بك، متجري").assertIsDisplayed()
  }

  @Test
  fun drawsTheMonthBarWithItsShareAsTheDescription() {
    show()

    compose.onNodeWithContentDescription("نسبة الشهر 75%").assertIsDisplayed()
  }

  @Test
  fun theToggleShowsAndHidesTheMovementList() {
    show()

    compose.onNodeWithText("سامي").assertDoesNotExist()
    compose.onNodeWithText("عرض سجل الحركات").performClick()
    compose.onNodeWithText("سامي").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("إغلاق سجل الحركات").performClick()
    compose.onNodeWithText("سامي").assertDoesNotExist()
  }

  @Test
  fun aPendingMovementShowsThePendingBadge() {
    show()

    compose.onNodeWithText("عرض سجل الحركات").performClick()
    compose.onNodeWithContentDescription("بانتظار التأكيد").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun tappingAMovementOpensItsContact() {
    var opened: String? = null
    show(onOpenContact = { opened = it })

    compose.onNodeWithText("عرض سجل الحركات").performClick()
    compose.onNodeWithText("سامي").performScrollTo().performClick()

    assertEquals("c1", opened)
  }

  @Test
  fun theRouteReadsTheOpenStore() {
    runBlocking { TestGraph.signedIn() }
        .use { fixture ->
          runBlocking {
            fixture.graph.accounts.update(STORE_A) { it.copy(storeName = "متجر النور") }
            lateinit var contactId: String
            fixture.seed { contactId = addContact("سامي", null, null, null, null).getOrThrow() }
            fixture.seed {
              addDebt(contactId, Direction.RECEIVABLE, Cents(123_456), null, null).getOrThrow()
            }
          }
          val model =
              HomeViewModel(
                  fixture.graph,
                  Clock.fixed(
                      Instant.parse("2026-10-05T09:00:00Z"),
                      ZoneId.of("Asia/Jerusalem"),
                  ),
              )

          compose.setContent { SadadTheme { HomeScreen(viewModel = model) } }

          compose.waitUntil(5_000) {
            compose.onAllNodesWithText("مرحبًا بك، متجر النور").fetchSemanticsNodes().isNotEmpty()
          }
          compose.onNodeWithTag(HomeTags.TOTAL).assertTextEquals("1,234.56 ₪")
        }
  }
}
