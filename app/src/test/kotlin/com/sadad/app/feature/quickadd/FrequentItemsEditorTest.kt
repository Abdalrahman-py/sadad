package com.sadad.app.feature.quickadd

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Default qualifiers: under PHONE_QUALIFIERS a text field inside a dialog never lets Robolectric
// idle.
@Config(application = UiTestApplication::class)
class FrequentItemsEditorTest {
  @get:Rule val compose = createComposeRule()

  private fun click(tag: String) =
      compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() }

  @Test
  fun theEditorAddsReordersAndRemovesItemsAndSavesTheResult() {
    var saved: List<String>? = null
    compose.setContent {
      SadadTheme {
        FrequentItemsEditor(listOf("حاجات", "كولا"), onSave = { saved = it }, onDismiss = {})
      }
    }

    compose.onNodeWithTag(FrequentItemsTags.FIELD).performTextInput(" خبز ")
    click(FrequentItemsTags.ADD)
    compose.onNodeWithTag(FrequentItemsTags.FIELD).performTextInput("كولا")
    compose.onNodeWithTag(FrequentItemsTags.ADD).assertIsNotEnabled()
    click(FrequentItemsTags.up(2))
    click(FrequentItemsTags.remove(0))
    click(FrequentItemsTags.SAVE)

    compose.runOnIdle { assertEquals(listOf("خبز", "كولا"), saved) }
  }
}
