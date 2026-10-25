package com.sadad.app.a11y

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.R
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.contacts.ContactDetailScreen
import com.sadad.app.feature.contacts.ContactDetailViewModel
import com.sadad.app.ui.UiTestApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The contact page at both font sizes (ticket 76). */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class ContactA11yTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()
  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test fun contactDetail() = contactDetail(NORMAL_FONT)

  @Test fun contactDetailAtLargestFont() = contactDetail(LARGEST_FONT)

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
      compose.waitUntil(AWAIT_MS) { model.state.value.loaded }
      compose.assertAccessible(hasText(context.getString(R.string.contacts_record_debt)))
    }
  }
}
