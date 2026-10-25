package com.sadad.app.a11y

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import com.sadad.app.feature.recovery.RecoveryContent
import com.sadad.app.ui.UiTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Recovery at both font sizes (ticket 76). */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class RecoveryA11yTest {
  @get:Rule val compose = createComposeRule()

  @Test fun recovery() = recovery(NORMAL_FONT)

  @Test fun recoveryAtLargestFont() = recovery(LARGEST_FONT)

  private fun recovery(fontScale: Float) {
    compose.showAt(fontScale) { RecoveryContent(status = "revoked") }
    compose.assertAccessible(hasText("تسجيل الهاتف كجهاز جديد"))
  }
}
