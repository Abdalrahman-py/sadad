package com.sadad.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Remove animations" (animator duration scale 0) reaches Compose as a [MotionDurationScale] of 0
 * from the window recomposer. With it, the animations that stay end at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class MotionTest {
  @get:Rule
  val compose =
      createComposeRule(
          effectContext =
              object : MotionDurationScale {
                override val scaleFactor = 0f
              }
      )

  @Test
  fun theToastSlidesInAtOnceWithAnimationsOff() {
    var message by mutableStateOf<String?>(null)
    compose.setContent { SadadTheme { SadadToastHost(message, onDismiss = {}) } }
    compose.mainClock.autoAdvance = false
    message = "تم الحفظ"
    compose.mainClock.advanceTimeByFrame()
    compose.mainClock.advanceTimeByFrame()
    val first = compose.onNodeWithText("تم الحفظ").getBoundsInRoot()
    compose.mainClock.advanceTimeBy(1_000)
    val end = compose.onNodeWithText("تم الحفظ").getBoundsInRoot()
    assertEquals(end, first)
  }
}
