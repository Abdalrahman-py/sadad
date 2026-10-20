package com.sadad.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class NavigationTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun bottomBarItemsShowTheRemainingPlaceholders() {
    compose.setContent { SadadApp(startDestination = History) }
    compose.onNodeWithTag(ScreenTag.HISTORY).assertIsDisplayed().assertTextEquals("سجل الحركات")
    compose.onNodeWithTag(ScreenTag.NAV_SETTINGS).performClick()
    compose.onNodeWithTag(ScreenTag.SETTINGS).assertIsDisplayed().assertTextEquals("الإعدادات")
    compose.onNodeWithTag(ScreenTag.NAV_HISTORY).performClick()
    compose.onNodeWithTag(ScreenTag.HISTORY).assertIsDisplayed().assertTextEquals("سجل الحركات")
  }
}
