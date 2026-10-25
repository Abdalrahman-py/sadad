package com.sadad.app.feature.people

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
              ContactsViewModel(fixture.graph, SavedStateHandle()).also {
                fixture.closeWithStore(it.viewModelScope)
              }
          compose.setContent {
            SadadTheme { ContactsScreen(viewModel = model, onOpenContact = {}) }
          }
          compose.waitUntil(30_000) { model.state.value.registered == 5000 }
          val last = model.state.value.rows[4999]

          compose.onNodeWithTag(PeopleTags.LIST).performScrollToIndex(4999)

          compose.onNodeWithText(last.name).assertIsDisplayed()
        }
  }

  @Test
  fun tappingAContactOpensItsRealDetailScreen() {
    val person = contact("سامي", contactId(1))
    val fixture = runBlocking { TestGraph.signedIn().also { it.seedCommands(listOf(person)) } }
    fixture.use {
      val list =
          ContactsViewModel(fixture.graph, SavedStateHandle()).also {
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
            composable<People> { ContactsScreen(list, { nav.navigate(ContactDetail(it)) }) }
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
