package com.sadad.app.a11y

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import com.sadad.app.export.MovementKind
import com.sadad.app.feature.home.HomeContent
import com.sadad.app.feature.home.HomeMovement
import com.sadad.app.feature.home.HomeTags
import com.sadad.app.feature.home.HomeUiState
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import java.time.Instant
import java.time.YearMonth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home at the normal font size and at the largest (2.0), on a 360 dp phone (ticket 76). */
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
}
