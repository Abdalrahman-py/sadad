package com.sadad.app.feature.quickadd

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sadad.app.a11y.PHONE_QUALIFIERS
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two amount-step chips the board draws. They were inert (`Chip(…) {}`), so no quick-add debt
 * could carry a due date or a note. This drives the chip, not the constructor: the dialog is the
 * observable seam.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class QuickAddChipsTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun newPersonDoesNotUseTheUnvalidatedInlineForm() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      var createdName: String? = null
      val model =
          QuickAddViewModel(fixture.graph, null, false, SavedStateHandle()).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      compose.setContent {
        SadadTheme {
          QuickAddScreen(
              null,
              false,
              {},
              {},
              onCreateContact = { createdName = it },
              viewModel = model,
          )
        }
      }
      compose.runOnIdle { model.onQueryChange("إبراهيم") }
      compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(QuickAddTags.ADD_NEW).fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithTag(QuickAddTags.ADD_NEW).performSemanticsAction(SemanticsActions.OnClick) {
        it()
      }
      org.junit.Assert.assertEquals("إبراهيم", createdName)
      org.junit.Assert.assertEquals(QuickAddStep.PICKER, model.state.value.step)
      runBlocking {
        assertTrue(checkNotNull(fixture.graph.current.value).outbox.snapshot().isEmpty())
      }
    }
  }

  @Test
  fun theDueChipOpensTheDatePicker() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      val contact = runBlocking {
        checkNotNull(fixture.graph.current.value)
            .repository
            .addContact("خالد", null, null, null, null)
            .getOrThrow()
            .also { fixture.settleAll() }
      }
      val model =
          QuickAddViewModel(fixture.graph, contact, false, SavedStateHandle(), 100).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      compose.setContent {
        SadadTheme {
          CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            QuickAddScreen(
                contactId = contact,
                payment = false,
                onClose = {},
                onOpenContact = {},
                onCreateContact = {},
                viewModel = model,
            )
          }
        }
      }
      compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(QuickAddTags.DUE_CHIP).fetchSemanticsNodes().isNotEmpty()
      }

      compose.onNodeWithTag(QuickAddTags.DUE_CHIP).performSemanticsAction(
          SemanticsActions.OnClick
      ) {
        it()
      }

      assertTrue(
          "the due chip opens a date picker",
          compose.onAllNodesWithTag(QuickAddTags.DUE_DIALOG).fetchSemanticsNodes().isNotEmpty(),
      )
    }
  }
}
