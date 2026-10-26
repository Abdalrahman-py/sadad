package com.sadad.app.feature.people

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.contacts.ContactDetailScreen
import com.sadad.app.feature.contacts.ContactDetailViewModel
import com.sadad.app.feature.contacts.ContactsTag
import com.sadad.app.feature.contacts.contact
import com.sadad.app.feature.contacts.contactId
import com.sadad.app.feature.contacts.debt
import com.sadad.app.feature.contacts.pageOf
import com.sadad.app.feature.contacts.seedCommands
import com.sadad.app.testing.TestGraph
import com.sadad.app.ui.ContactDetail
import com.sadad.app.ui.People
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The الأشخاص tab on a real store: the list's length, a row's parts, and the tap that opens one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class PeopleScreenTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  private val context: Context = ApplicationProvider.getApplicationContext()

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
          val model =
              PeopleViewModel(fixture.graph, SavedStateHandle()).also {
                fixture.closeWithStore(it.viewModelScope)
              }
          compose.setContent {
            SadadTheme { PeopleScreen(onOpenContact = {}, viewModel = model) }
          }
          compose.waitUntil(30_000) { model.state.value.registered == 5000 }
          val last = model.state.value.rows[4999]

          compose.onNodeWithTag(PeopleTags.LIST).performScrollToIndex(4999)

          compose.onNodeWithText(last.name).assertIsDisplayed()
        }
  }

  @Test
  fun aRowSaysThePhoneTailTheActivityAndTheBalanceWord() {
    val person = contact("سامي", contactId(1), phone = "+970****3456")
    val fixture = runBlocking {
      TestGraph.signedIn().also {
        it.seedCommands(listOf(person, debt(person.entityId, amount = 12_500, minute = 1)))
      }
    }
    fixture.use {
      val model =
          PeopleViewModel(fixture.graph, SavedStateHandle()).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      compose.setContent { SadadTheme { PeopleScreen(onOpenContact = {}, viewModel = model) } }
      compose.waitUntil(10_000) { model.state.value.loaded && model.state.value.rows.size == 1 }

      compose.onNodeWithText("سامي").assertIsDisplayed()
      compose.onNodeWithText("…456").assertIsDisplayed()
      // BalanceWord speaks its figure, so the row reads «عليه 125 شيكل» aloud.
      compose.onNodeWithContentDescription("عليه 125 شيكل").assertIsDisplayed()
      compose.onNodeWithTag(PeopleTags.COUNT).assertIsDisplayed()
    }
  }

  @Test
  fun tappingAContactOpensItsRealDetailScreen() {
    val person = contact("سامي", contactId(1))
    val fixture = runBlocking { TestGraph.signedIn().also { it.seedCommands(listOf(person)) } }
    fixture.use {
      val list =
          PeopleViewModel(fixture.graph, SavedStateHandle()).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      val detail =
          ContactDetailViewModel(fixture.graph, person.entityId, context).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      compose.setContent {
        val nav = rememberNavController()
        SadadTheme {
          NavHost(navController = nav, startDestination = People()) {
            composable<People> {
              PeopleScreen(onOpenContact = { nav.navigate(ContactDetail(it)) }, viewModel = list)
            }
            composable<ContactDetail> {
              ContactDetailScreen(detail, { nav.popBackStack() }, {}, {}, {})
            }
          }
        }
      }
      compose.waitUntil(10_000) { list.state.value.loaded && detail.state.value.loaded }
      compose.onNodeWithTag(PeopleTags.row(person.entityId)).performClick()
      compose.onNodeWithTag(ContactsTag.TITLE).assertIsDisplayed()
      compose.onNodeWithText("سامي").assertIsDisplayed()
      compose.onNodeWithText("تسجيل دفعة").assertIsDisplayed()
    }
  }
}
