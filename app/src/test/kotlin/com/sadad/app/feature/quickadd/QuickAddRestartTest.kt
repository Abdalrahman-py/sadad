package com.sadad.app.feature.quickadd

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.QuickAdd
import com.sadad.app.ui.SadadShell
import com.sadad.app.ui.ScreenTag
import com.sadad.app.ui.SyncDot
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import com.sadad.ledger.CommandType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The bottom bar's «+» on Quick Add starts a new entry, asking first when typing would be lost. */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class QuickAddRestartTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun aTypedAmountAsksFirstAndConfirmingStartsABlankEntry() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      val saved = SavedStateHandle()
      val model = fixture.model(saved)
      model.state.first { it.loaded }
      model.pick(contact)
      model.press("1")
      model.press("+")
      model.press("2")
      model.onNoteChange("خبز")
      model.onDueDateChange("2026-12-01")

      model.requestRestart(context)
      assertTrue(model.state.value.restartAsk)
      assertEquals("nothing is dropped before the answer", "2", model.state.value.amount)

      model.confirmRestart(context)
      val state = model.state.value
      assertEquals(QuickAddStep.PICKER, state.step)
      assertFalse(state.restartAsk)
      assertEquals("", state.amount)
      assertTrue(state.terms.isEmpty())
      assertEquals("", state.note)
      assertNull(state.dueDate)
      assertNull(state.selected)
      // Recreated after process death, the entry stays blank.
      assertEquals("", saved.get<String>("quickadd:amount"))
      assertEquals("", saved.get<String>("quickadd:terms"))
      assertEquals("", saved.get<String>("quickadd:note"))
      assertNull(saved.get<String>("quickadd:due-date"))
    }
  }

  @Test
  fun keepingTheEntryLeavesItAsItWas() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      val model = fixture.model()
      model.state.first { it.loaded }
      model.pick(contact)
      model.press("7")

      model.requestRestart(context)
      model.dismissRestart()

      val state = model.state.value
      assertFalse(state.restartAsk)
      assertEquals(QuickAddStep.AMOUNT, state.step)
      assertEquals("7", state.amount)
      assertEquals(contact, state.selected?.id)
    }
  }

  @Test
  fun withNothingTypedTheEntryResetsAtOnce() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      val model = fixture.model()
      model.state.first { it.loaded }
      // A chosen person alone is not worth a dialog.
      model.pick(contact)

      model.requestRestart(context)

      val state = model.state.value
      assertFalse(state.restartAsk)
      assertEquals(QuickAddStep.PICKER, state.step)
      assertNull(state.selected)
    }
  }

  @Test
  fun aRestartInsideTheUndoWindowQueuesTheEntryOnce() = runTest {
    TestGraph.signedIn().use { fixture ->
      val contact = fixture.settledContact("خالد")
      val store = checkNotNull(fixture.graph.current.value)
      // Longer than the test waits, so only the restart can have queued the row.
      val model = fixture.model(undoWindowMs = 5_000)
      model.state.first { it.loaded }
      model.pick(contact)
      model.press("4")
      model.press("0")
      model.save(context)
      model.state.first { it.step == QuickAddStep.DONE }

      model.requestRestart(context)
      assertFalse("the saved entry is not lost, so no dialog", model.state.value.restartAsk)
      assertEquals(QuickAddStep.PICKER, model.state.value.step)
      // Every later way out finds nothing left to queue.
      model.leave(context)
      model.requestRestart(context)

      val deadline = System.currentTimeMillis() + 5_000
      while (store.outbox.snapshot().isEmpty() && System.currentTimeMillis() < deadline) {
        Thread.sleep(10)
      }
      Thread.sleep(300)
      assertEquals(CommandType.DEBT_CREATE, store.outbox.snapshot().single().type)
    }
  }

  @Test
  fun theRecordButtonOnTheAmountStepAsksAndConfirmingShowsThePicker() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      val contact = runBlocking { fixture.settledContact("خالد") }
      val model =
          QuickAddViewModel(fixture.graph.quickAddSource(), contact, false, SavedStateHandle(), 100)
              .also { fixture.closeWithStore(it.viewModelScope) }
      compose.setContent {
        SadadTheme {
          CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            SadadShell(
                navController = rememberNavController(),
                startDestination = QuickAdd(contact),
                syncDot = SyncDot.NONE,
            ) {
              composable<QuickAdd> {
                QuickAddScreen(
                    contactId = contact,
                    payment = false,
                    onClose = {},
                    onOpenContact = {},
                    onOpenTrash = {},
                    viewModel = model,
                )
              }
            }
          }
        }
      }
      compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(QuickAddTags.KEYPAD).fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithTag(QuickAddTags.key("1")).performClick()
      compose.onNodeWithTag(QuickAddTags.key("2")).performClick()
      compose.runOnIdle { assertEquals("12", model.state.value.amount) }

      compose.onNodeWithTag(ScreenTag.NAV_RECORD).performClick()
      compose.onNodeWithTag(QuickAddTags.RESTART).assertExists()
      compose.onNodeWithText("تجاهل المدخل الحالي؟").assertExists()

      compose.onNodeWithText("تجاهل").performClick()
      compose.waitUntil(5_000) {
        compose.onAllNodesWithTag(QuickAddTags.SEARCH).fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithTag(QuickAddTags.RESTART).assertDoesNotExist()
      compose.runOnIdle { assertEquals("", model.state.value.amount) }
    }
  }

  private suspend fun TestGraph.settledContact(name: String): String =
      checkNotNull(graph.current.value)
          .repository
          .addContact(name, null, null, null, null)
          .getOrThrow()
          .also { settleAll() }

  private fun TestGraph.model(
      saved: SavedStateHandle = SavedStateHandle(),
      undoWindowMs: Long = 300,
  ): QuickAddViewModel =
      QuickAddViewModel(graph.quickAddSource(), null, false, saved, undoWindowMs).also {
        closeWithStore(it.viewModelScope)
      }
}
