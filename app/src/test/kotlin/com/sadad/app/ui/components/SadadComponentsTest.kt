package com.sadad.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The shared buttons sit on stock M3 parts with 12dp corners, and a press never shrinks them. */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class SadadComponentsTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun theButtonShapeIs12dp() {
    assertEquals(RoundedCornerShape(12.dp), SadadButtonShape)
  }

  @Test
  fun aPressedButtonKeepsItsSize() {
    compose.setContent {
      SadadTheme {
        Column {
          SadadButtonStyle.entries.forEach { style ->
            SadadButton(style.name, onClick = {}, Modifier.testTag(style.name), style = style)
          }
        }
      }
    }
    compose.mainClock.autoAdvance = false
    SadadButtonStyle.entries.forEach { style ->
      val node = compose.onNodeWithTag(style.name)
      node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
      val before = node.getBoundsInRoot()
      node.performTouchInput { down(center) }
      compose.mainClock.advanceTimeBy(500)
      assertEquals(style.name, before, node.getBoundsInRoot())
      node.performTouchInput { up() }
      compose.mainClock.advanceTimeBy(500)
    }
  }
}
