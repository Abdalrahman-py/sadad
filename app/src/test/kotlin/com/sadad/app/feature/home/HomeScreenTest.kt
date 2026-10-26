package com.sadad.app.feature.home

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import com.sadad.ledger.Cents
import java.time.Instant
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The three things a shop does from Home: see the two figures, open the people who are late, and —
 * in a new store — start the first entry. The bell announces its count to a screen reader instead
 * of only drawing a badge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class HomeScreenTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  private var openedPeople: String? = "not-called"
  private var openedContact: String? = null
  private var recorded = 0
  private var openedActivity = 0
  private var openedNotifications = 0

  private val state =
      HomeUiState(
          storeName = "متجر النور",
          month = YearMonth.of(2026, 10),
          owedToStore = Cents(1_248_050),
          storeOwes = Cents(124_000),
          monthDebts = Cents(320_000),
          monthPayments = Cents(195_000),
          monthPaymentsCount = 14,
          collectionPercent = 61,
          followUps =
              listOf(
                  HomeFollowUp("c1", "مريم حسن", 3, Cents(112_000)),
                  HomeFollowUp("c2", "أحمد سالم", null, Cents(25_000)),
              ),
          followUpCount = 2,
      )

  @Test
  fun theBellAnnouncesHowManyAreNew() {
    show(state, unread = 2)

    compose.onNodeWithContentDescription("الإشعارات، 2 جديدة").assertIsDisplayed().performClick()

    assertEquals(1, openedNotifications)
  }

  @Test
  fun theBellWithoutUnreadIsJustNotifications() {
    show(state, unread = 0)

    compose.onNodeWithContentDescription("الإشعارات").assertIsDisplayed()
  }

  @Test
  fun showAllOpensPeopleWithTheLateFilter() {
    show(state)

    compose.onNodeWithTag(HomeTags.SHOW_ALL).performScrollTo().performClick()

    assertEquals("late", openedPeople)
  }

  @Test
  fun theHeroStatesBothFiguresAndTheMonthsShare() {
    show(state)

    compose.onNodeWithTag(HomeTags.OWED).assertTextEquals("₪ 12,480.50")
    compose.onNodeWithTag(HomeTags.OWES).assertTextEquals("₪ 1,240.00")
    compose.onNodeWithText("61٪ من ديون الشهر").assertIsDisplayed()
  }

  @Test
  fun aRowSaysHowLateAndOpensThePerson() {
    show(state)

    compose.onNodeWithText("متأخر 3 أيام").performScrollTo().assertIsDisplayed()
    compose.onNodeWithText("يستحق اليوم").performScrollTo().assertIsDisplayed()

    compose.onNodeWithTag(HomeTags.follow("c1")).performScrollTo().performClick()

    assertEquals("c1", openedContact)
  }

  @Test
  fun eachRowHasASpokenReminderButton() {
    show(state)

    compose
        .onNodeWithContentDescription("تذكير مريم حسن على واتساب")
        .performScrollTo()
        .assertIsDisplayed()
    compose
        .onNodeWithContentDescription("تذكير أحمد سالم على واتساب")
        .performScrollTo()
        .assertIsDisplayed()
  }

  @Test
  fun theEmptyStoreOffersTheFirstEntry() {
    val empty = HomeUiState(storeName = "متجر النور", month = YearMonth.of(2026, 10), empty = true)
    show(empty)

    compose.onNodeWithText("دفترك جاهز").assertIsDisplayed()
    compose.onNodeWithTag(HomeTags.EMPTY_CTA).performScrollTo().performClick()

    assertEquals(1, recorded)
    assertTrue(
        "the hero is not drawn for a new store",
        compose.onAllNodesWithTagAbsent(HomeTags.OWED),
    )
  }

  @Test
  fun theOfflineBannerCountsWhatWaits() {
    val offline =
        state.copy(
            offline = HomeOffline(queued = 3, leaseEndsAt = Instant.parse("2026-10-25T17:00:00Z"))
        )
    show(offline)

    compose.onNodeWithTag(HomeTags.OFFLINE).assertIsDisplayed()
    compose.onNodeWithText("لا يوجد اتصال · 3 إدخالات بانتظار الإرسال").assertIsDisplayed()
  }

  @Test
  fun theMonthLineOpensActivity() {
    show(state)

    compose.onNodeWithTag(HomeTags.MONTH_LINE).performClick()

    assertEquals(1, openedActivity)
  }

  private fun show(state: HomeUiState, unread: Int = 0) {
    compose.setContent {
      SadadTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          HomeContent(
              state = state,
              unread = unread,
              onOpenContact = { openedContact = it },
              onOpenPeople = { openedPeople = it },
              onOpenNotifications = { openedNotifications++ },
              onRecord = { recorded++ },
              onOpenActivity = { openedActivity++ },
          )
        }
      }
    }
  }

  private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagAbsent(
      tag: String
  ): Boolean = onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isEmpty()
}
