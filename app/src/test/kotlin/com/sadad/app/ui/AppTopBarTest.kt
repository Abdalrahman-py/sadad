package com.sadad.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.sadad.app.ui.theme.SadadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The bar every tab shares: a bell that speaks its count. */
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

  private fun show(unread: Int) {
    compose.setContent {
      SadadTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          AppTopBarContent(
              unread = unread,
              onOpenNotifications = { opened++ },
          )
        }
      }
    }
  }
}
