package com.sadad.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shell's last placeholder, rendered under `UiTestApplication`: a real screen's default
 * ViewModel needs `SadadApplication`'s graph, so this test starts on Settings (still a placeholder)
 * and checks the shell and its bottom bar render. Navigating to the real screens is each feature
 * screen test's job, with a `TestGraph`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class NavigationTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun theSettingsPlaceholderAndTheBottomBarRender() {
    compose.setContent { SadadApp(startDestination = Settings) }

    compose.onNodeWithTag(ScreenTag.SETTINGS).assertIsDisplayed().assertTextEquals("الإعدادات")
    compose.onNodeWithTag(ScreenTag.NAV_HOME).assertIsDisplayed()
    compose.onNodeWithTag(ScreenTag.NAV_CONTACTS).assertIsDisplayed()
    compose.onNodeWithTag(ScreenTag.NAV_HISTORY).assertIsDisplayed()
    compose.onNodeWithTag(ScreenTag.NAV_SETTINGS).assertIsDisplayed()
  }
}
