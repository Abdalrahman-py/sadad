package com.sadad.app.feature.debt

import android.content.Context
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.R
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class DebtFormScreenTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun theReviewShowsTheGroupedAmountAfterTheLargeAmountConfirm() {
    val (fixture, contact) = graphWithContact()
    fixture.use {
      val model = DebtFormViewModel(fixture.graph, contact, SavedStateHandle())
      compose.setContent { SadadTheme { DebtFormScreen(viewModel = model, onSaved = {}) } }
      compose.waitUntil(10_000) { model.state.value.loaded }

      compose.onNodeWithTag(DebtTag.AMOUNT).performTextInput("5000")
      compose
          .onNodeWithTag(DebtTag.AMOUNT)
          .assertContentDescriptionEquals(text(R.string.debt_amount_label))
      compose.onNodeWithTag(DebtTag.SAVE).performScrollTo().performClick()

      compose.onNodeWithText(text(R.string.debt_anomaly_title)).assertIsDisplayed()
      compose.onNodeWithText(text(R.string.debt_anomaly_confirm)).performClick()

      compose.onNodeWithText(text(R.string.debt_review_title)).assertIsDisplayed()
      compose.onNodeWithText("5,000 ₪", substring = true).assertIsDisplayed()
    }
  }

  @Test
  fun theRemoveActionClearsTheDueDate() {
    val (fixture, contact) = graphWithContact()
    fixture.use {
      val model = DebtFormViewModel(fixture.graph, contact, SavedStateHandle())
      compose.setContent { SadadTheme { DebtFormScreen(viewModel = model, onSaved = {}) } }
      compose.waitUntil(10_000) { model.state.value.loaded }
      compose.runOnIdle { model.onDueDateChange("2026-11-01") }
      compose.onNodeWithText("2026-11-01").assertIsDisplayed()

      compose.onNodeWithTag(DebtTag.CLEAR_DUE_DATE).performClick()

      compose.onNodeWithText(text(R.string.debt_due_hint)).assertIsDisplayed()
      assertNull(model.state.value.dueDate)
    }
  }

  /** One signed-in store with one settled Contact, and the Contact's id. */
  private fun graphWithContact(): Pair<TestGraph, String> = runBlocking {
    val fixture = TestGraph.signedIn()
    val contact =
        checkNotNull(fixture.graph.current.value)
            .repository
            .addContact("سامي", "+970599123456", null, null, null)
            .getOrThrow()
    fixture.settleAll()
    fixture to contact
  }

  private fun text(id: Int): String =
      ApplicationProvider.getApplicationContext<Context>().getString(id)
}
