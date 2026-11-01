package com.sadad.app.a11y

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.quickadd.QuickAddScreen
import com.sadad.app.feature.quickadd.QuickAddTags
import com.sadad.app.feature.quickadd.QuickAddViewModel
import com.sadad.app.feature.quickadd.quickAddSource
import com.sadad.app.ui.UiTestApplication
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The recording flow at both font sizes: the picker, and the keypad whose twelve keys are the only
 * way to type an amount. Every target has to be reachable and spoken, at 360 dp and at the largest
 * font, because this is the screen a shop uses with one hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class QuickAddA11yTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()
  private val closeables = mutableListOf<AutoCloseable>()

  @After fun close() = closeables.forEach(AutoCloseable::close)

  @Test fun picker() = picker(NORMAL_FONT)

  @Test fun pickerAtLargestFont() = picker(LARGEST_FONT)

  @Test
  fun theKeysOfTheAmountStepAreSpokenAndBigEnough() = compose.withStore { fixture ->
    val model = modelFor(fixture, seedPerson = true)
    compose.showAt(NORMAL_FONT) { screen(model) }
    val person = model.state.value.people.first()
    compose.onNodeWithTag(QuickAddTags.person(person.id)).performClick()
    compose.waitForIdle()

    compose.assertAccessible(hasTestTag(QuickAddTags.SAVE))
    for (key in listOf("1", "5", ".", "del")) {
      compose.onNodeWithTag(QuickAddTags.key(key)).assertIsDisplayed()
    }
    // The two keys whose face is not a digit are spoken, not just drawn.
    compose.onNodeWithContentDescription("حذف").assertIsDisplayed()
    compose.onNodeWithContentDescription("فاصلة عشرية").assertIsDisplayed()
  }

  @Test
  fun theDoneStepOffersUndoAndAnotherEntry() = compose.withStore { fixture ->
    val model = modelFor(fixture, seedPerson = true)
    compose.showAt(NORMAL_FONT) { screen(model) }
    compose.onNodeWithTag(QuickAddTags.person(model.state.value.people.first().id)).performClick()
    compose.waitForIdle()
    compose.onNodeWithTag(QuickAddTags.key("2")).performClick()
    compose.onNodeWithTag(QuickAddTags.key("5")).performClick()
    compose.onNodeWithTag(QuickAddTags.SAVE).performClick()
    compose.waitForIdle()

    compose.assertAccessible(
        hasTestTag(QuickAddTags.UNDO),
        hasTestTag(QuickAddTags.ANOTHER),
    )
  }

  private fun picker(fontScale: Float) = compose.withStore { fixture ->
    val model = modelFor(fixture, seedPerson = true)
    compose.showAt(fontScale) { screen(model) }
    // The last row a finger has to reach at this font size.
    compose.assertAccessible(hasTestTag(QuickAddTags.person(model.state.value.people.first().id)))
  }

  private fun modelFor(
      fixture: com.sadad.app.testing.TestGraph,
      seedPerson: Boolean,
  ): QuickAddViewModel {
    if (seedPerson) {
      awaiting {
        checkNotNull(fixture.graph.current.value)
            .repository
            .addContact("خالد يوسف", "+970599123482", null, null, null)
            .getOrThrow()
        fixture.settleAll()
      }
    }
    val model = QuickAddViewModel(fixture.graph.quickAddSource(), null, false, SavedStateHandle())
    awaiting { model.state.first { it.loaded && it.people.isNotEmpty() } }
    return model
  }

  @androidx.compose.runtime.Composable
  private fun screen(model: QuickAddViewModel) {
    QuickAddScreen(
        contactId = null,
        payment = false,
        onClose = {},
        onOpenContact = {},
        onCreateContact = {},
        viewModel = model,
    )
  }
}
