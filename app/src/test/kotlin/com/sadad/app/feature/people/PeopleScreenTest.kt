package com.sadad.app.feature.people

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
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
import com.sadad.app.contactRecords
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.contacts.ContactDetailScreen
import com.sadad.app.feature.contacts.ContactDetailViewModel
import com.sadad.app.feature.contacts.ContactsTag
import com.sadad.app.feature.contacts.contact
import com.sadad.app.feature.contacts.contactDetailSource
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
  fun confirmedPeopleAndIncompleteInputsHaveSeparateCounts() {
    compose.setContent {
      SadadTheme { PeopleContent(PeopleState(registered = 13, incomplete = 6)) }
    }
    compose.onNodeWithTag(PeopleTags.COUNT).assertIsDisplayed()
    compose.onNodeWithText("13").assertIsDisplayed()
    compose.onNodeWithText("6 إضافات محفوظة لم تكتمل مزامنتها").assertIsDisplayed()
  }

  @Test
  fun addingAndChoosingAFilterLiveTogetherBelowSearch() {
    var added = 0
    var chosen = PeopleFilter.ALL
    compose.setContent {
      var state by remember {
        mutableStateOf(PeopleState(counts = PeopleFilter.entries.associateWith { 4 }))
      }
      SadadTheme {
        PeopleContent(
            state,
            onAdd = { added++ },
            onFilter = {
              chosen = it
              state = state.copy(filterChoice = it)
            },
        )
      }
    }
    compose.onNodeWithTag("people:sort").assertDoesNotExist()
    compose.onNodeWithText("آخر حركة").assertDoesNotExist()
    val search = compose.onNodeWithTag(PeopleTags.SEARCH).fetchSemanticsNode().boundsInRoot
    val add = compose.onNodeWithTag(PeopleTags.ADD).fetchSemanticsNode().boundsInRoot
    val filter = compose.onNodeWithTag("people:filter").fetchSemanticsNode().boundsInRoot
    assertTrue(add.top >= search.bottom)
    assertEquals(add.center.y, filter.center.y, 1f)
    compose.onNodeWithTag(PeopleTags.ADD).performClick()
    assertEquals(1, added)

    val labels = listOf("الكل", "عليهم", "لهم", "متأخرون", "موردون")
    PeopleFilter.entries.zip(labels).forEach { (value, label) ->
      compose.onNodeWithTag("people:filter").performClick()
      labels.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
      compose.onNodeWithText(label).performClick()
      assertEquals(value, chosen)
      compose.onNodeWithTag("people:filter").performClick()
      compose.onNodeWithText(label).assertIsSelected()
      compose.onNodeWithText(label).performClick()
    }
  }

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
              PeopleViewModel(fixture.graph.contactRecords(), SavedStateHandle()).also {
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
  fun aRowSaysThePhoneTheActivityAndTheBalanceWord() {
    // The restyle prints the number itself rather than its tail, so the row is asserted on the
    // number the contact carries.
    val phone = "+970****3456"
    val person = contact("سامي", contactId(1), phone = phone)
    val fixture = runBlocking {
      TestGraph.signedIn().also {
        it.seedCommands(listOf(person, debt(person.entityId, amount = 12_500, minute = 1)))
      }
    }
    fixture.use {
      val model =
          PeopleViewModel(fixture.graph.contactRecords(), SavedStateHandle()).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      compose.setContent { SadadTheme { PeopleScreen(onOpenContact = {}, viewModel = model) } }
      compose.waitUntil(10_000) { model.state.value.loaded && model.state.value.rows.size == 1 }

      compose.onNodeWithText("سامي").assertIsDisplayed()
      compose.onNodeWithText(phone).assertIsDisplayed()
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
          PeopleViewModel(fixture.graph.contactRecords(), SavedStateHandle()).also {
            fixture.closeWithStore(it.viewModelScope)
          }
      val detail =
          ContactDetailViewModel(fixture.graph.contactDetailSource(), person.entityId, context)
              .also {
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
      compose.onNodeWithText("استلام دفعة").assertIsDisplayed()
    }
  }
}
