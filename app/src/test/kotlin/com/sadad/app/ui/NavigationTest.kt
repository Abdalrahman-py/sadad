package com.sadad.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
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
  fun bottomBarItemsShowTheirPlaceholders() {
    // Home is a real screen now (HomeScreenTest covers it); the rest are still placeholders.
    compose.setContent { SadadApp(startDestination = Contacts) }
    compose.onNodeWithTag(ScreenTag.CONTACTS).assertIsDisplayed().assertTextEquals("الزبائن")
    compose.onNodeWithTag(ScreenTag.NAV_HISTORY).performClick()
    compose.onNodeWithTag(ScreenTag.HISTORY).assertIsDisplayed().assertTextEquals("سجل الحركات")
    compose.onNodeWithTag(ScreenTag.NAV_SETTINGS).performClick()
    compose.onNodeWithTag(ScreenTag.SETTINGS).assertIsDisplayed().assertTextEquals("الإعدادات")
  }

  @Test
  fun contactDetailShowsItsId() {
    lateinit var nav: NavHostController
    compose.setContent {
      nav = rememberNavController()
      SadadApp(startDestination = Contacts, navController = nav)
    }
    nav.navigate(ContactDetail("id-1"))
    compose.onNodeWithTag(ScreenTag.CONTACT_DETAIL).assertIsDisplayed()
    compose.onNodeWithText("id-1", substring = true).assertIsDisplayed()
  }
}
