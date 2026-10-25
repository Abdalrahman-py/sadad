package com.sadad.app.a11y

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import com.sadad.app.feature.auth.MainDispatcherRule
import com.sadad.app.feature.settings.SettingsKit
import com.sadad.app.feature.settings.SettingsScreen
import com.sadad.app.ui.UiTestApplication
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The المتجر tab (today's settings list) at both font sizes (ticket 76). */
@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class, qualifiers = PHONE_QUALIFIERS)
class StoreA11yTest {
  @get:Rule val compose = createComposeRule()
  @get:Rule val main = MainDispatcherRule()
  private val closeables = mutableListOf<AutoCloseable>()

  @After fun close() = closeables.forEach(AutoCloseable::close)

  @Test fun store() = store(NORMAL_FONT)

  @Test fun storeAtLargestFont() = store(LARGEST_FONT)

  private fun store(fontScale: Float) = compose.withStore { fixture ->
    val model = SettingsKit(fixture).also(closeables::add).viewModel()
    awaiting { model.state.first { it.loaded } }
    compose.showAt(fontScale) { SettingsScreen(settingsViewModel = model) }
    // The last row of the list, so the whole list has to be reachable by scrolling.
    compose.assertAccessible(hasText("المطور : orca"))
  }
}
