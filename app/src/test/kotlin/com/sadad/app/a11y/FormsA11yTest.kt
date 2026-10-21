package com.sadad.app.a11y

import android.content.Context
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.R
import com.sadad.app.feature.auth.AuthTags
import com.sadad.app.feature.auth.LoginScreen
import com.sadad.app.feature.auth.LoginViewModel
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.contactform.ContactFormScreen
import com.sadad.app.feature.contactform.ContactFormViewModel
import com.sadad.app.feature.contacts.ContactDetailScreen
import com.sadad.app.feature.contacts.ContactDetailViewModel
import com.sadad.app.feature.debt.DebtFormScreen
import com.sadad.app.feature.debt.DebtFormViewModel
import com.sadad.app.feature.debt.DebtTag
import com.sadad.app.feature.payment.PaymentFormScreen
import com.sadad.app.feature.payment.PaymentFormViewModel
import com.sadad.app.feature.payment.PaymentTag
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.ledger.Cents
import com.sadad.ledger.Direction
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Contact detail, the Contact, Debt and Payment forms and Login, at both font sizes (ticket 76).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class FormsA11yTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test fun contactDetail() = contactDetail(NORMAL_FONT)

  @Test fun contactDetailAtLargestFont() = contactDetail(LARGEST_FONT)

  @Test fun contactForm() = contactForm(NORMAL_FONT)

  @Test fun contactFormAtLargestFont() = contactForm(LARGEST_FONT)

  @Test fun debtForm() = debtForm(NORMAL_FONT)

  @Test fun debtFormAtLargestFont() = debtForm(LARGEST_FONT)

  @Test fun paymentForm() = paymentForm(NORMAL_FONT)

  @Test fun paymentFormAtLargestFont() = paymentForm(LARGEST_FONT)

  @Test fun login() = login(NORMAL_FONT)

  @Test fun loginAtLargestFont() = login(LARGEST_FONT)

  private fun contactDetail(fontScale: Float) {
    val (fixture, contact) = graphWithDebt()
    fixture.use {
      val model =
          ContactDetailViewModel(fixture.graph, contact, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      compose.showAt(fontScale) {
        ContactDetailScreen(
            viewModel = model,
            onBack = {},
            onRecordDebt = {},
            onRecordPayment = {},
            onEdit = {},
        )
      }
      compose.waitUntil(WAIT_MS) { model.state.value.loaded }
      compose.assertAccessible(hasText(context.getString(R.string.contacts_record_debt)))
    }
  }

  /** The add-person form with the first-debt switch on, so its extra fields are checked too. */
  private fun contactForm(fontScale: Float) {
    val fixture = runBlocking { TestGraph.signedIn() }
    fixture.use {
      val scope = checkNotNull(fixture.graph.current.value) { "the fixture has no open store" }
      val model = ContactFormViewModel(scope.repository, STORE_A, context, SavedStateHandle(), null)
      model.onFirstDebtChange(true)
      compose.showAt(fontScale) { ContactFormScreen(model) }
      compose.assertAccessible(hasText(context.getString(R.string.contactform_save_add)))
    }
  }

  private fun debtForm(fontScale: Float) {
    val (fixture, contact) = graphWithDebt()
    fixture.use {
      val model = DebtFormViewModel(fixture.graph, contact, SavedStateHandle())
      compose.showAt(fontScale) { DebtFormScreen(viewModel = model, onSaved = {}) }
      compose.waitUntil(WAIT_MS) { model.state.value.loaded }
      compose.assertAccessible(hasTestTag(DebtTag.AMOUNT), hasTestTag(DebtTag.SAVE))
    }
  }

  private fun paymentForm(fontScale: Float) {
    val (fixture, contact) = graphWithDebt()
    fixture.use {
      val model = PaymentFormViewModel(fixture.graph, contact, SavedStateHandle())
      compose.showAt(fontScale) { PaymentFormScreen(viewModel = model, onSaved = {}) }
      compose.waitUntil(WAIT_MS) { model.state.value.openBalance == Cents(15_000) }
      compose.assertAccessible(hasTestTag(PaymentTag.AMOUNT), hasTestTag(PaymentTag.SAVE))
    }
  }

  private fun login(fontScale: Float) {
    TestGraph.unsigned().use { fixture ->
      val model = LoginViewModel(fixture.graph, SavedStateHandle(), context)
      compose.showAt(fontScale) { LoginScreen(viewModel = model, onNavigate = {}) }
      compose.assertAccessible(
          hasTestTag(AuthTags.USERNAME),
          hasTestTag(AuthTags.PASSWORD),
          hasTestTag(AuthTags.LOGIN_BUTTON),
      )
    }
  }

  /** One signed-in store with one settled Contact who owes 150 in two Debts. */
  private fun graphWithDebt(): Pair<TestGraph, String> = runBlocking {
    withTimeout(WAIT_MS) {
      val fixture = TestGraph.signedIn()
      val repository = checkNotNull(fixture.graph.current.value).repository
      val contact = repository.addContact("سامي", "+970599123456", null, null, null).getOrThrow()
      fixture.settleAll()
      for (amount in listOf(10_000L, 5_000L)) {
        repository.addDebt(contact, Direction.RECEIVABLE, Cents(amount), null, null).getOrThrow()
        fixture.settleAll()
      }
      fixture to contact
    }
  }

  private companion object {
    const val WAIT_MS = 10_000L
  }
}
