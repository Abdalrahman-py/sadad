package com.sadad.app.feature.updates

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class InstallerRecoveryScreenTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun waitingForTheSystemOffersAnEnabledButtonToReopenItsConfirmation() {
    val app = ApplicationProvider.getApplicationContext<Context>()
    var launches = 0
    val context =
        object : ContextWrapper(app) {
          override fun startActivity(intent: Intent) {
            launches++
          }
        }
    val center = UpdateCenter.get(app)
    center.confirmInstallation(context, Intent("android.content.pm.action.CONFIRM_INSTALL"))
    compose.setContent {
      SadadTheme { Actions(center.state.value, center, context, null) }
    }
    compose.onNodeWithText("فتح نافذة تثبيت التحديث").assertIsEnabled().performClick()
    assertEquals(2, launches)
    center.installFailed(UpdateError.INSTALL_CANCELLED)
  }
}
