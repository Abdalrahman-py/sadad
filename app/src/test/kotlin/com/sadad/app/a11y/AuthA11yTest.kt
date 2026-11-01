package com.sadad.app.a11y

import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.feature.auth.AuthTags
import com.sadad.app.feature.auth.LoginScreen
import com.sadad.app.feature.auth.LoginViewModel
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.auth.authSource
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The sign-in screen at both font sizes (ticket 76). */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class AuthA11yTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test fun login() = login(NORMAL_FONT)

  @Test fun loginAtLargestFont() = login(LARGEST_FONT)

  private fun login(fontScale: Float) {
    TestGraph.unsigned().use { fixture ->
      val model = LoginViewModel(fixture.graph.authSource(), SavedStateHandle(), context)
      compose.showAt(fontScale) { LoginScreen(viewModel = model, onNavigate = {}) }
      compose.assertAccessible(
          hasTestTag(AuthTags.USERNAME),
          hasTestTag(AuthTags.PASSWORD),
          hasTestTag(AuthTags.LOGIN_BUTTON),
      )
    }
  }
}
