package com.sadad.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import com.sadad.app.ui.theme.SadadTheme
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
}
