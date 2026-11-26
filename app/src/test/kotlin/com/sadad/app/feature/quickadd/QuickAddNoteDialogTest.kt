package com.sadad.app.feature.quickadd

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The note dialog's chips come from [FrequentItems]. */
// Default qualifiers: under PHONE_QUALIFIERS a text field inside a dialog never lets Robolectric
// idle.
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class QuickAddNoteDialogTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun theNoteDialogShowsTheSavedFrequentItemsAndATapFillsTheNote() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val items =
        FrequentItems.create(scope, File(temporary.root, FrequentItems.FILE_NAME), listOf("كولا"))
    runBlocking { items.save(listOf("خبز", "حليب")).join() }
    val fixture = runBlocking { TestGraph.signedIn() }
    try {
      fixture.use {
        val contact = runBlocking {
          checkNotNull(fixture.graph.current.value)
              .repository
              .addContact("خالد", null, null, null, null)
              .getOrThrow()
              .also { fixture.settleAll() }
        }
        val model =
            QuickAddViewModel(
                    fixture.graph.quickAddSource(),
                    contact,
                    false,
                    SavedStateHandle(),
                    100,
                )
                .also { fixture.closeWithStore(it.viewModelScope) }
        compose.setContent {
          SadadTheme {
            QuickAddScreen(
                contactId = contact,
                payment = false,
                onClose = {},
                onOpenContact = {},
                onOpenTrash = {},
                viewModel = model,
                frequentItems = items,
            )
          }
        }
        compose.waitUntil(5_000) {
          compose.onAllNodesWithTag(QuickAddTags.NOTE_CHIP).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(QuickAddTags.NOTE_CHIP).performSemanticsAction(
            SemanticsActions.OnClick
        ) {
          it()
        }
        compose.waitUntil(5_000) {
          compose.onAllNodesWithText("خبز").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("حليب").assertExists()
        assertTrue(compose.onAllNodesWithText("كولا").fetchSemanticsNodes().isEmpty())

        compose.onNodeWithText("خبز").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("حفظ").performSemanticsAction(SemanticsActions.OnClick) { it() }

        compose.runOnIdle { assertEquals("خبز", model.state.value.note) }
      }
    } finally {
      runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
    }
  }
}
