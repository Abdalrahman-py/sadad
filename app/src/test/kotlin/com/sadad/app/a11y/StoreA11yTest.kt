package com.sadad.app.a11y

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.settings.SettingsKit
import com.sadad.app.feature.settings.SettingsTags
import com.sadad.app.feature.settings.SettingsViewModel
import com.sadad.app.feature.settings.StoreScreen
import com.sadad.app.ui.UiTestApplication
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The المتجر tab at both font sizes (tickets 76 and 86). */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class StoreA11yTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()
  private val closeables = mutableListOf<AutoCloseable>()

  @After fun close() = closeables.forEach(AutoCloseable::close)

  @Test fun store() = store(NORMAL_FONT)

  @Test fun storeAtLargestFont() = store(LARGEST_FONT)

  @Test
  fun theLockSwitchIsAnnouncedWithItsState() = compose.withStore { fixture ->
    val model = SettingsKit(fixture).also(closeables::add).viewModel(biometricAvailable = true)
    awaiting { model.state.first { it.loaded } }
    render(model, NORMAL_FONT)

    compose
        .onNode(isToggleable() and hasAnyDescendant(hasText("قفل التطبيق")), useUnmergedTree = true)
        .assertIsOff()
  }

  private fun store(fontScale: Float) = compose.withStore { fixture ->
    val model = SettingsKit(fixture).also(closeables::add).viewModel(biometricAvailable = true)
    awaiting { model.state.first { it.loaded } }
    render(model, fontScale)
    // The last thing on the page, so the whole list has to be reachable by scrolling.
    compose.assertAccessible(hasTestTag(SettingsTags.VERSION))
  }

  private fun render(model: SettingsViewModel, fontScale: Float) {
    compose.showAt(fontScale) {
      StoreScreen(
          onOpenTrash = {},
          onOpenRecovery = {},
          onChangePassword = {},
          onAddStore = {},
          onNeedsLogin = {},
          onOpenOperationsLog = {},
          settingsViewModel = model,
      )
    }
  }
}
