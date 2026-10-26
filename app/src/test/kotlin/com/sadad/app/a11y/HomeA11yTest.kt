package com.sadad.app.a11y

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import com.sadad.app.feature.home.HomeContent
import com.sadad.app.feature.home.HomeFollowUp
import com.sadad.app.feature.home.HomeOffline
import com.sadad.app.feature.home.HomeTags
import com.sadad.app.feature.home.HomeUiState
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import java.time.Instant
import java.time.YearMonth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Home at the normal font size and at the largest (2.0), on a 360 dp phone: the hero, the reminder
 * buttons in `تحتاج متابعة`, the empty card's button and the offline banner all have to stay
 * reachable and spoken when the text triples.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class HomeA11yTest {
  @get:Rule val compose = createComposeRule()

  @Test fun home() = home(NORMAL_FONT)

  @Test fun homeAtLargestFont() = home(LARGEST_FONT)

  private fun home(fontScale: Float) {
    compose.showAt(fontScale) {
      HomeContent(
          state =
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
              ),
          unread = 2,
      )
    }
    // The last thing a finger has to reach at this font size.
    compose.assertAccessible(hasTestTag(HomeTags.remind("c2")))
  }

  @Test
  fun theEmptyStoreIsReachableAtTheLargestFont() {
    compose.showAt(LARGEST_FONT) {
      HomeContent(
          state =
              HomeUiState(storeName = "متجر النور", month = YearMonth.of(2026, 10), empty = true)
      )
    }
    compose.assertAccessible(hasTestTag(HomeTags.EMPTY_CTA))
  }

  @Test
  fun theOfflineBannerIsReachableAtTheLargestFont() {
    compose.showAt(LARGEST_FONT) {
      HomeContent(
          state =
              HomeUiState(
                  storeName = "متجر النور",
                  month = YearMonth.of(2026, 10),
                  owedToStore = Cents(12_000),
                  offline =
                      HomeOffline(
                          queued = 3,
                          leaseEndsAt = Instant.parse("2026-10-25T17:00:00Z"),
                      ),
              ),
          unread = 1,
      )
    }
    compose.assertAccessible(hasTestTag(HomeTags.BELL))
  }
}
