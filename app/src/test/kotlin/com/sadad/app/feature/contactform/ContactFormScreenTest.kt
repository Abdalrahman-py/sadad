package com.sadad.app.feature.contactform

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.STORE_A
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

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class ContactFormScreenTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  private fun TestGraph.model(): ContactFormViewModel {
    val scope = checkNotNull(graph.current.value) { "the fixture has no open store" }
    return ContactFormViewModel(scope.repository, STORE_A, context, SavedStateHandle(), null)
  }

  @Test
  fun theReviewDialogShowsTheFormattedAmount() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      val model = fixture.model()
      model.onNameChange("سامي")
      model.onFirstDebtChange(true)
      model.onDebtAmountChange("150.5")
      model.save()

      compose.setContent { SadadTheme { ContactFormScreen(model) } }

      compose.onNodeWithText("مراجعة الشخص والدين").assertIsDisplayed()
      compose.onNodeWithText("150.5 ₪").assertIsDisplayed()
    }
  }

  @Test
  fun theFirstDebtFieldsHideWhenTheSwitchIsOff() {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      val model = fixture.model()
      compose.setContent { SadadTheme { ContactFormScreen(model) } }

      assertTrue(compose.onAllNodesWithText("مبلغ الدين").fetchSemanticsNodes().isEmpty())

      model.onFirstDebtChange(true)
      compose.waitUntil {
        compose.onAllNodesWithText("مبلغ الدين").fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithText("مبلغ الدين").assertExists()
    }
  }
}
