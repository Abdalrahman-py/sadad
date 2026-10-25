package com.sadad.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.sadad.app.ui.theme.SadadDarkPalette
import com.sadad.app.ui.theme.SadadLightPalette
import com.sadad.app.ui.theme.SadadTheme
import com.sadad.app.ui.theme.sadadPalette
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = UiTestApplication::class)
class ThemeTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun darkThemeUsesTheJavaPalette() {
    var primary: Color? = null
    compose.setContent { SadadTheme(dark = true) { primary = MaterialTheme.colorScheme.primary } }
    assertEquals(Color(0xFF256C5A), primary)
  }

  @Test
  @Config(qualifiers = "notnight")
  fun chosenDarkThemeOverridesALightDevice() {
    var dark: Boolean? = null
    compose.setContent { SadadTheme(dark = true) { dark = sadadPalette.isDark } }
    assertEquals(true, dark)
  }

  @Test
  @Config(qualifiers = "night")
  fun chosenLightThemeOverridesADarkDevice() {
    var dark: Boolean? = null
    compose.setContent { SadadTheme(dark = false) { dark = sadadPalette.isDark } }
    assertEquals(false, dark)
  }

  @Test
  fun theRedesignTokensMatchTheBoards() {
    assertEquals(Color(0xFF5B6F63), SadadLightPalette.muted)
    assertEquals(Color(0xFFAB323A), SadadLightPalette.owes)
    assertEquals(Color(0xFFFFE1E1), SadadLightPalette.owesSoft)
    assertEquals(Color(0xFF9A5A13), SadadLightPalette.credit)
    assertEquals(Color(0xFFFFF1DD), SadadLightPalette.creditSoft)
    assertEquals(Color(0xFFF59A9F), SadadDarkPalette.owes)
    assertEquals(Color(0xFF583B3D), SadadDarkPalette.owesSoft)
    assertEquals(Color(0xFFFFD08A), SadadDarkPalette.credit)
    assertEquals(Color(0xFF4A3A22), SadadDarkPalette.creditSoft)
  }
}
