package com.sadad.app.a11y

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.contactpicker.ContactPickerScreen
import com.sadad.app.feature.contactpicker.ContactPickerTags
import com.sadad.app.feature.contactpicker.ContactPickerViewModel
import com.sadad.app.feature.contactpicker.DeviceContact
import com.sadad.app.feature.contactpicker.FakeDeviceContacts
import com.sadad.app.feature.contactpicker.SadadPeople
import com.sadad.app.ui.UiTestApplication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The contacts list is personal data shown full-screen, so every row, the search field and the
 * chooser have to be spoken and in reach at both font sizes — including the greyed rows, which
 * announce why they cannot be picked for WhatsApp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class ContactPickerA11yTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()

  private val contacts =
      arrayOf(
          DeviceContact("سارة", listOf("0599123456")),
          DeviceContact("نمر", listOf("0299123456")),
          DeviceContact("آمنة", listOf("0598111222", "0299123450")),
      )

  private fun model(vararg contacts: DeviceContact) =
      ContactPickerViewModel(
          FakeDeviceContacts(*contacts),
          MutableStateFlow(SadadPeople(emptyList(), emptyList())),
          grantedNow = { true },
      )

  @Test
  fun theRowListSitsAtTheNormalFont() = runTest {
    compose.showAt(NORMAL_FONT) { ContactPickerScreen(model(*contacts)) }

    compose.assertAccessible(hasTestTag(ContactPickerTags.row("آمنة")))
  }

  @Test
  fun theRowListSitsAtTheLargestFont() = runTest {
    compose.showAt(LARGEST_FONT) { ContactPickerScreen(model(*contacts)) }

    compose.assertAccessible(hasTestTag(ContactPickerTags.row("آمنة")))
  }

  @Test
  fun theGreyedRowAnnouncesWhyItCannotBeUsed() = runTest {
    val picker = model(*contacts)
    compose.showAt(NORMAL_FONT) { ContactPickerScreen(picker) }

    compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("نمر"))
    compose.onAllNodesWithText("غير صالح للواتساب").assertCountEquals(2)
    compose.assertAccessible(hasTestTag(ContactPickerTags.row("نمر")))
  }

  @Test
  fun theChooserNumbersAreSpokenAndInReach() = runTest {
    val picker = model(*contacts)
    compose.showAt(NORMAL_FONT) { ContactPickerScreen(picker) }
    compose.onNodeWithText("آمنة").performClick()

    compose.assertAccessible(contactNumber("0598111222"))
  }

  private fun contactNumber(raw: String) = hasTestTag(ContactPickerTags.number(raw))
}
