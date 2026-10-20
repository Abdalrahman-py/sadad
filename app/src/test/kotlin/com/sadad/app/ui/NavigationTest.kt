package com.sadad.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sadad.app.ui.theme.SadadTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The shell's bottom bar, rendered under `UiTestApplication` with stub destinations: every real
 * screen builds its ViewModel from `SadadApplication`'s graph, which Compose tests do not have, so
 * the real screens are each feature screen test's job, with a `TestGraph`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class NavigationTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun theBottomBarShowsTheFourTabsAndMovesBetweenThem() {
    compose.setContent {
      SadadTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          val navController = rememberNavController()
          Column(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f)) {
              NavHost(navController = navController, startDestination = Home) {
                composable<Home> { Text("stub-home") }
                composable<Contacts> { Text("stub-contacts") }
                composable<History> { Text("stub-history") }
                composable<Settings> { Text("stub-settings") }
              }
            }
            SadadBottomBar(navController)
          }
        }
      }
    }

    compose.onNodeWithText("stub-home").assertIsDisplayed()
    compose.onNodeWithTag(ScreenTag.NAV_HOME).assertIsDisplayed().assertIsSelected()
    compose.onNodeWithTag(ScreenTag.NAV_CONTACTS).assertIsDisplayed()
    compose.onNodeWithTag(ScreenTag.NAV_HISTORY).assertIsDisplayed()
    compose.onNodeWithTag(ScreenTag.NAV_SETTINGS).assertIsDisplayed()

    compose.onNodeWithTag(ScreenTag.NAV_CONTACTS).performClick()
    compose.onNodeWithText("stub-contacts").assertIsDisplayed()
    compose.onNodeWithTag(ScreenTag.NAV_CONTACTS).assertIsSelected()

    compose.onNodeWithTag(ScreenTag.NAV_HISTORY).performClick()
    compose.onNodeWithText("stub-history").assertIsDisplayed()

    compose.onNodeWithTag(ScreenTag.NAV_SETTINGS).performClick()
    compose.onNodeWithText("stub-settings").assertIsDisplayed()
    compose.onNodeWithTag(ScreenTag.NAV_SETTINGS).assertIsSelected()
  }
}
