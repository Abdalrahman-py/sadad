package com.sadad.app.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.sadad.app.ui.theme.SadadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The bar every tab shares: the tab's title beside a bell that speaks its count. */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class AppTopBarTest {
  @get:Rule val compose = createComposeRule()

  private var opened = 0

  @Test
  fun theBellAnnouncesHowManyAreNew() {
    show(unread = 2)

    compose.onNodeWithContentDescription("الإشعارات، 2 جديدة").assertIsDisplayed().performClick()

    assertEquals(1, opened)
  }

  @Test
  fun theBellWithoutUnreadIsJustNotifications() {
    show(unread = 0)

    compose.onNodeWithContentDescription("الإشعارات").assertIsDisplayed()
  }

  @Test
  fun theBellIsAFullTouchTargetWithAMaterialBadge() {
    show(unread = 3)

    // The touch area, as TalkBack and the a11y checks measure it, not the 40dp drawn circle.
    val touch = compose.onNodeWithTag(BELL_TAG).fetchSemanticsNode().touchBoundsInRoot
    val minimum = with(compose.density) { 48.dp.toPx() }
    assertTrue(touch.width >= minimum && touch.height >= minimum)
    // The badge draws the count; the button's label is what TalkBack reads.
    compose.onNodeWithText("3", useUnmergedTree = true).assertIsDisplayed()
    compose.onNodeWithContentDescription("الإشعارات، 3 جديدة").assertIsDisplayed()
  }

  @Test
  fun eachTabShowsItsTitleBesideTheBellInOneRow() {
    lateinit var nav: NavHostController
    compose.setContent {
      SadadTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          nav = rememberNavController()
          SadadShell(navController = nav, startDestination = Home, syncDot = SyncDot.NONE) {
            composable<Home> { Text("stub-home") }
            composable<People> { Text("stub-people") }
            composable<Activity> { Text("stub-activity") }
            composable<Store> { Text("stub-store") }
          }
        }
      }
    }
    for ((tab, title) in
        listOf(
            ScreenTag.NAV_HOME to "الرئيسية",
            ScreenTag.NAV_PEOPLE to "الأشخاص",
            ScreenTag.NAV_ACTIVITY to "آخر الحركات",
            ScreenTag.NAV_STORE to "المتجر",
        )) {
      compose.onNodeWithTag(tab).performClick()
      val inBar = hasText(title) and hasAnyAncestor(hasTestTag(TOP_BAR_TAG))
      // One title node in the bar, and no second header outside it but the tab's own label.
      val titleBounds =
          compose.onNode(inBar, useUnmergedTree = true).assertIsDisplayed().getBoundsInRoot()
      compose
          .onAllNodes(
              hasText(title) and
                  !hasAnyAncestor(hasTestTag(TOP_BAR_TAG)) and
                  !hasAnyAncestor(hasTestTag(tab)),
              useUnmergedTree = true,
          )
          .assertCountEquals(0)
      val bell = compose.onNodeWithTag(BELL_TAG).getBoundsInRoot()
      assertTrue(
          "$title and the bell share a row",
          titleBounds.top < bell.bottom && bell.top < titleBounds.bottom,
      )
      // Right to left: the title starts at the right, the bell sits at the left.
      assertTrue(titleBounds.left > bell.right)
    }
  }

  @Test
  fun aScreenPutsItsActionsBesideTheBell() {
    compose.setContent {
      SadadTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          SadadShell(rememberNavController(), Home, SyncDot.NONE) {
            composable<Home> {
              TopBarActions { Text("action-export") }
              Text("stub-home")
            }
          }
        }
      }
    }

    compose
        .onNode(hasText("action-export") and hasAnyAncestor(hasTestTag(TOP_BAR_TAG)))
        .assertIsDisplayed()
  }

  private fun show(unread: Int) {
    compose.setContent {
      SadadTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          AppTopBarContent(
              title = "الرئيسية",
              unread = unread,
              onOpenNotifications = { opened++ },
          )
        }
      }
    }
  }
}
