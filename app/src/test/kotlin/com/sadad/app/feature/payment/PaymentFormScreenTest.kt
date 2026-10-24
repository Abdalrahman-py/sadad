package com.sadad.app.feature.payment

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
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
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import com.sadad.ledger.PaymentMethod
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class PaymentFormScreenTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun aContactWithNoOpenDebtSeesTheEmptyState() {
    val (fixture, contact) = graphWithDebts()
    fixture.use {
      val model = PaymentFormViewModel(fixture.graph, contact, SavedStateHandle())
      compose.setContent { SadadTheme { PaymentFormScreen(viewModel = model, onSaved = {}) } }
      compose.waitUntil(10_000) { model.state.value.loaded }

      compose.onNodeWithText(text(R.string.payment_no_open_balance)).assertIsDisplayed()
      compose.onNodeWithText(text(R.string.payment_no_open_balance_detail)).assertIsDisplayed()
    }
  }

  @Test
  fun selectingAPaymentMethodFromTheMenuUpdatesTheReview() {
    val (fixture, contact) = graphWithDebts(10_000)
    fixture.use {
      val model = PaymentFormViewModel(fixture.graph, contact, SavedStateHandle())
      compose.setContent { SadadTheme { PaymentFormScreen(viewModel = model, onSaved = {}) } }
      compose.waitUntil(10_000) { model.state.value.openBalance == Cents(10_000) }

      compose.onNodeWithTag(PaymentTag.METHOD).performScrollTo().performClick()
      compose.onNodeWithText(text(R.string.payment_method_bank)).performClick()
      compose.runOnIdle { assertEquals(PaymentMethod.BANK, model.state.value.method) }

      compose.onNodeWithTag(PaymentTag.AMOUNT).performScrollTo().performTextInput("25")
      compose.onNodeWithTag(PaymentTag.SAVE).performScrollTo().performClick()
      compose.onNodeWithText(text(R.string.payment_review_title)).assertIsDisplayed()
      compose
          .onNodeWithText(
              text(R.string.payment_review_method).format(text(R.string.payment_method_bank)),
              substring = true,
          )
          .assertIsDisplayed()
    }
  }

  @Test
  fun theReviewListsOneLinePerPart() {
    val (fixture, contact) = graphWithDebts(10_000, 5_000)
    fixture.use {
      val model = PaymentFormViewModel(fixture.graph, contact, SavedStateHandle())
      compose.setContent { SadadTheme { PaymentFormScreen(viewModel = model, onSaved = {}) } }
      compose.waitUntil(10_000) { model.state.value.openBalance == Cents(15_000) }

      compose.onNodeWithTag(PaymentTag.AMOUNT).performTextInput("120")
      compose.onNodeWithTag(PaymentTag.SAVE).performScrollTo().performClick()

      compose.onNodeWithText(text(R.string.payment_review_title)).assertIsDisplayed()
      val review = compose.onNodeWithText(PART_PREFIX, substring = true)
      review.assertTextContains(": 100 ₪", substring = true)
      review.assertTextContains(": 20 ₪", substring = true)
    }
  }

  /** One signed-in store with one settled Contact and the given settled receivable Debts. */
  private fun graphWithDebts(vararg cents: Long): Pair<TestGraph, String> = runBlocking {
    val fixture = TestGraph.signedIn()
    val repository = checkNotNull(fixture.graph.current.value).repository
    val contact = repository.addContact("سامي", "+970****3456", null, null, null).getOrThrow()
    fixture.settleAll()
    for (amount in cents) {
      repository.addDebt(contact, Direction.RECEIVABLE, Cents(amount), null, null).getOrThrow()
      fixture.settleAll()
    }
    fixture to contact
  }

  private fun text(id: Int): String =
      ApplicationProvider.getApplicationContext<Context>().getString(id)

  private companion object {
    /** The start of every part line the review shows. */
    const val PART_PREFIX = "دين بتاريخ"
  }
}
