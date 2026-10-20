package com.sadad.app.feature.contacts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.SavedStateHandle
import com.sadad.app.data.session.StoredInstallation
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.testing.STORE_A
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class ContactsScreensTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  @Test
  fun theListScrollsToTheLastOfFiveThousandRows() {
    runBlocking {
      TestGraph.signedIn().also { fixture ->
        fixture.seed {
          applyPage(pageOf((0 until 5000).map { contact("شخص $it", contactId(it), minute = it) }))
        }
      }
    }
        .use { fixture ->
          val model = ContactsViewModel(fixture.graph, SavedStateHandle())
          compose.setContent {
            SadadTheme { ContactsScreen(viewModel = model, onOpenContact = {}) }
          }
          compose.waitUntil(30_000) { model.state.value.registered == 5000 }
          val last = model.state.value.rows[4999]

          compose.onNodeWithTag(ContactsTag.LIST).performScrollToIndex(4999)

          compose.onNodeWithText(last.name).assertIsDisplayed()
        }
  }

  @Test
  fun restrictedPaymentAndDeletePermissionsReplaceTheButtons() {
    val person = contact("سامي", contactId(1), minute = 0)
    runBlocking {
      TestGraph.signedIn().also { fixture ->
        fixture.seed { applyPage(pageOf(listOf(person))) }
        fixture.graph.accounts.update(STORE_A) {
          it.copy(
              installation =
                  StoredInstallation(
                      status = "active",
                      generation = 1,
                      permissions = mapOf("registerPayments" to false, "deleteContacts" to false),
                  )
          )
        }
      }
    }
        .use { fixture ->
          val model = ContactDetailViewModel(fixture.graph, person.entityId)
          compose.setContent {
            SadadTheme {
              ContactDetailScreen(
                  viewModel = model,
                  onBack = {},
                  onRecordDebt = {},
                  onRecordPayment = {},
                  onEdit = {},
              )
            }
          }
          compose.waitUntil(10_000) { model.state.value.loaded }

          compose.onNodeWithText("تسجيل الدفعات مقيّد").assertIsDisplayed()
          compose.onNodeWithText("تسجيل دفعة").assertDoesNotExist()
          compose.onNodeWithText("حذف الزبون").assertDoesNotExist()
        }
  }
}
