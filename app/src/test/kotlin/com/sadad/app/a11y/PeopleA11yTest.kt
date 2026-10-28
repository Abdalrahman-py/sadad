package com.sadad.app.a11y

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.people.PeopleFilter
import com.sadad.app.feature.people.PeopleScreen
import com.sadad.app.feature.people.PeopleTags
import com.sadad.app.feature.people.PeopleViewModel
import com.sadad.app.ui.UiTestApplication
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The People tab at both font sizes (ticket 76), and its chips saying which one is on. */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class PeopleA11yTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  @Test fun people() = people(NORMAL_FONT)

  @Test fun peopleAtLargestFont() = people(LARGEST_FONT)

  private fun people(fontScale: Float) = compose.withStore { fixture ->
    awaiting { seedContacts(fixture) }
    val model =
        PeopleViewModel(fixture.graph, SavedStateHandle()).also {
          fixture.closeWithStore(it.viewModelScope)
        }
    awaiting { model.state.first { it.registered > 0 } }
    compose.showAt(fontScale) { PeopleScreen(onOpenContact = {}, viewModel = model) }
    compose.assertAccessible(hasTestTag(PeopleTags.SEARCH))
  }

  @Test
  fun theChipsSayWhichOneIsOnAndSwitchWhenTapped() = compose.withStore { fixture ->
    awaiting { seedContacts(fixture) }
    val model =
        PeopleViewModel(fixture.graph, SavedStateHandle()).also {
          fixture.closeWithStore(it.viewModelScope)
        }
    awaiting { model.state.first { it.registered > 0 } }
    compose.showAt(NORMAL_FONT) { PeopleScreen(onOpenContact = {}, viewModel = model) }

    compose.onNode(hasText("الكل") and isSelectable()).assertIsSelected()
    compose.onNode(hasText("عليهم") and isSelectable()).assertIsNotSelected()

    compose.onNode(hasText("عليهم") and isSelectable()).performClick()

    compose.onNode(hasText("عليهم") and isSelectable()).assertIsSelected()
    compose.onNode(hasText("الكل") and isSelectable()).assertIsNotSelected()
    assertEquals(PeopleFilter.OWES, model.state.value.filter)
    // Eight settled Contacts with no entry owe nothing, so the chip empties the list.
    compose.onNode(hasText("لا توجد نتائج")).assertExists()
  }
}
