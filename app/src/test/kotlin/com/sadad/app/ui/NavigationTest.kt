package com.sadad.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sadad.app.ui.theme.SadadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shell and its bottom bar, rendered under `UiTestApplication` with stub destinations: every
 * real screen builds its ViewModel from `SadadApplication`'s graph, which Compose tests do not
 * have, so the real screens are each feature screen test's job, with a `TestGraph`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class NavigationTest {
  @get:Rule val compose = createComposeRule()
  private lateinit var nav: NavHostController

  private fun showShell() {
    compose.setContent {
      SadadTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          nav = rememberNavController()
          SadadShell(navController = nav, startDestination = Home, syncDot = SyncDot.NONE) {
            composable<Home> { Text("stub-home") }
            composable<People> { Text("stub-people") }
            composable<Activity> { Text("stub-activity") }
            composable<Store> { Text("stub-store") }
            composable<QuickAdd> { Text("stub-quick-add") }
            composable<ContactForm> { Text("stub-contact-form") }
          }
        }
      }
    }
  }

  @Test
  fun theBarShowsFiveItemsInRightToLeftOrder() {
    showShell()
    val items =
        listOf(
            ScreenTag.NAV_HOME to "الرئيسية",
            ScreenTag.NAV_PEOPLE to "الأشخاص",
            ScreenTag.NAV_RECORD to "تسجيل دين أو دفعة",
            ScreenTag.NAV_ACTIVITY to "الحركات",
            ScreenTag.NAV_STORE to "المتجر",
        )
    val order = items.map { (tag, spoken) ->
      compose
          .onNodeWithTag(tag)
          .assertIsDisplayed()
          .assertContentDescriptionEquals(spoken)
          .getBoundsInRoot()
          .left
    }
    // Right to left: each item starts left of the one before it.
    assertEquals(order.sortedDescending(), order)
    // The visible labels.
    listOf("الرئيسية", "الأشخاص", "تسجيل", "الحركات", "المتجر").forEach {
      compose.onNodeWithText(it).assertIsDisplayed()
    }
  }

  @Test
  fun tappingEachTabSelectsIt() {
    showShell()
    compose.onNodeWithText("stub-home").assertIsDisplayed()
    compose.onNodeWithTag(ScreenTag.NAV_HOME).assertIsSelected()
    for ((tag, stub) in
        listOf(
            ScreenTag.NAV_PEOPLE to "stub-people",
            ScreenTag.NAV_ACTIVITY to "stub-activity",
            ScreenTag.NAV_STORE to "stub-store",
            ScreenTag.NAV_HOME to "stub-home",
        )) {
      compose.onNodeWithTag(tag).performClick()
      compose.onNodeWithText(stub).assertIsDisplayed()
      compose.onNodeWithTag(tag).assertIsSelected()
    }
  }

  @Test
  fun recordOpensQuickAddAndTheBarHides() {
    showShell()
    compose.onNodeWithTag(ScreenTag.NAV_RECORD).performClick()
    compose.onNodeWithText("stub-quick-add").assertIsDisplayed()
    compose.onNodeWithTag(ScreenTag.NAV_HOME).assertDoesNotExist()
  }

  @Test
  fun theBarHidesOnTheContactForm() {
    showShell()
    compose.runOnIdle { nav.navigate(ContactForm("c1")) }
    compose.onNodeWithText("stub-contact-form").assertIsDisplayed()
    compose.onNodeWithTag(ScreenTag.NAV_HOME).assertDoesNotExist()
  }
}
