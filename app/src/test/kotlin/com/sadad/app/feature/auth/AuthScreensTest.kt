package com.sadad.app.feature.auth

import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class AuthScreensTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun theLoginButtonIsDisabledWhileBusy() {
    TestGraph.unsigned().use { fixture ->
      fixture.server.enqueue(
          MockResponse.Builder()
              .body(
                  """{"access_token":"e30.eyJhcHBfbWV0YWRhdGEiOnt9fQ.signature","refresh_token":"refresh-a","expires_at":${java.time.Instant.now().epochSecond + 3_600},"user":{"id":"${com.sadad.app.testing.STORE_A}"}}"""
              )
              .headersDelay(3, TimeUnit.SECONDS)
              .build()
      )
      var navigated: Any? = null
      val model = LoginViewModel(fixture.graph, SavedStateHandle(), context)
      compose.setContent {
        LoginScreen(viewModel = model, onNavigate = { navigated = it })
      }
      compose.onNodeWithTag(AuthTags.USERNAME).performTextInput("store-a")
      compose.onNodeWithTag(AuthTags.PASSWORD).performTextInput("password-store-a")

      // The restyled card is taller than the default test viewport, so the button starts below the
      // fold and a click there never lands: scroll to it the way a user would.
      compose.onNodeWithTag(AuthTags.LOGIN_BUTTON).performScrollTo()
      compose.onNodeWithTag(AuthTags.LOGIN_BUTTON).performClick()
      compose.waitForIdle()

      compose.onNodeWithTag(AuthTags.LOGIN_BUTTON).assertIsNotEnabled()
      assertNull(navigated)
    }
  }
}
