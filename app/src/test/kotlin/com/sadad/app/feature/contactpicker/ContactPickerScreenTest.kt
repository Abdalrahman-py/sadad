package com.sadad.app.feature.contactpicker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.ui.UiTestApplication
import com.sadad.app.ui.theme.SadadTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The list's entry states on the screen: the explanation, the denial, and the «don't ask again»
 * settings door; the list itself, its search, and the chooser of a contact with several numbers.
 * Permission states are driven on the view model — the launcher's own flow needs the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class ContactPickerScreenTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  private val people = MutableStateFlow(SadadPeople(emptyList(), emptyList()))
  private val results = mutableListOf<PickedContact>()
  private var closed = false

  private fun model(vararg contacts: DeviceContact, granted: Boolean = false) =
      ContactPickerViewModel(
          FakeDeviceContacts(*contacts),
          people,
          grantedNow = { granted },
      ) {
        results.add(it)
      }

  private val CONTACTS =
      arrayOf(
          DeviceContact("أحمد عبد", listOf("0501234567")),
          DeviceContact("سارة", listOf("0599123456")),
          DeviceContact("نمر", listOf("0299123456")),
          DeviceContact("هبة", listOf("+970599123457", "0299123450")),
      )

  private fun screen(model: ContactPickerViewModel) {
    compose.setContent {
      SadadTheme { ContactPickerScreen(model, onResult = {}, onBack = { closed = true }) }
    }
    compose.waitForIdle()
  }

  @Test
  fun theExplanationComesFirstAndNotNowClosesThePicker() = runTest {
    val model = model()
    screen(model)

    compose
        .onNodeWithText("نقرأ جهات الاتصال على جهازك لتختار منها، ولا نرفعها.")
        .assertIsDisplayed()
    compose.onNodeWithText("متابعة").assertExists()
    compose.onNodeWithText("ليس الآن").performClick()

    assertTrue(closed)
  }

  @Test
  fun aDeniedPermissionExplainsAndKeepsTheSettingsDoorShut() = runTest {
    val model = model()
    model.onPermissionResult(granted = false, canAskAgain = true)
    screen(model)

    compose
        .onNodeWithText(
            "لم يُسمح بقراءة جهات الاتصال. يمكنك إضافة الشخص يدويًا أو تفعيلها من الإعدادات."
        )
        .assertIsDisplayed()
    compose.onNodeWithTag(ContactPickerTags.SETTINGS).assertDoesNotExist()
  }

  @Test
  fun deniedForGoodOffersTheSettingsDoor() = runTest {
    val model = model()
    model.onPermissionResult(granted = false, canAskAgain = false)
    screen(model)

    compose
        .onNodeWithText(
            "لم يُسمح بقراءة جهات الاتصال. يمكنك إضافة الشخص يدويًا أو تفعيلها من الإعدادات."
        )
        .assertIsDisplayed()
    compose.onNodeWithTag(ContactPickerTags.SETTINGS).assertIsDisplayed()
  }

  @Test
  fun theGrantedListShowsEveryContactWithANumber() = runTest {
    val model = model(*CONTACTS, granted = true)
    screen(model)

    compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("هبة"))
    compose.onNodeWithText("هبة").assertIsDisplayed()
    compose.onNodeWithText("لم يُسمح بقراءة جهات الاتصال").assertDoesNotExist()
  }

  @Test
  fun theSearchFieldFiltersTheListOnFoldedArabic() = runTest {
    val model = model(*CONTACTS, granted = true)
    screen(model)

    compose.onNodeWithTag(ContactPickerTags.SEARCH).performTextInput("احمد")

    compose.onNodeWithText("أحمد عبد").assertExists()
    compose.onNodeWithText("سارة").assertDoesNotExist()
  }

  @Test
  fun theChooserShowsTheNumbersTheOwnerWroteAndReturnsThePick() = runTest {
    val model = model(*CONTACTS, granted = true)
    screen(model)

    compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("هبة"))
    compose.onNodeWithText("هبة").performClick()

    compose.onNodeWithTag(ContactPickerTags.number("+970599123457")).assertIsDisplayed()
    compose.onNodeWithTag(ContactPickerTags.number("0299123450")).assertIsDisplayed()

    compose.onNodeWithTag(ContactPickerTags.number("+970599123457")).performClick()

    assertEquals(listOf(PickedContact.New("هبة", "0599123457", "970")), results)
  }
}
