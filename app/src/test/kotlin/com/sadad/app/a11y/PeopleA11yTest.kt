package com.sadad.app.a11y

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.people.ContactsScreen
import com.sadad.app.feature.people.ContactsViewModel
import com.sadad.app.feature.people.PeopleTags
import com.sadad.app.ui.UiTestApplication
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The People list at both font sizes (ticket 76). */
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
        ContactsViewModel(fixture.graph, SavedStateHandle()).also {
          fixture.closeWithStore(it.viewModelScope)
        }
    awaiting { model.state.first { it.registered > 0 } }
    compose.showAt(fontScale) { ContactsScreen(viewModel = model, onOpenContact = {}) }
    compose.assertAccessible(hasTestTag(PeopleTags.SEARCH))
  }
}
