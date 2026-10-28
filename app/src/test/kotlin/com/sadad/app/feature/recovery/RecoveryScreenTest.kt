package com.sadad.app.feature.recovery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class RecoveryScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun aRevokedInstallationOffersRegisteringAsANewDevice() {
    compose.setContent { SadadTheme { RecoveryContent(status = "revoked") } }

    // The card is the last thing on a screen that scrolls, so scroll to it before asserting.
    compose.onNodeWithText("تسجيل الهاتف كجهاز جديد").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun anActiveInstallationDoesNotOfferRegisteringAgain() {
    compose.setContent { SadadTheme { RecoveryContent(status = "active") } }

    compose.onNodeWithText("تسجيل الهاتف كجهاز جديد").assertDoesNotExist()
  }
}
