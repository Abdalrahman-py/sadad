package com.sadad.app.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules the theme tokens keep: one type scale, the 4dp grid and near-grey dark surfaces. */
class SadadThemeTest {
  // The styles the app sets; displayLarge and displayMedium stay Material's and nothing reads them.
  private val styles: Map<String, TextStyle> =
      with(SadadTypography) {
        mapOf(
            "displaySmall" to displaySmall,
            "headlineLarge" to headlineLarge,
            "headlineMedium" to headlineMedium,
            "headlineSmall" to headlineSmall,
            "titleLarge" to titleLarge,
            "titleMedium" to titleMedium,
            "titleSmall" to titleSmall,
            "bodyLarge" to bodyLarge,
            "bodyMedium" to bodyMedium,
            "bodySmall" to bodySmall,
            "labelLarge" to labelLarge,
            "labelMedium" to labelMedium,
            "labelSmall" to labelSmall,
        )
      }

  @Test
  fun onlyTheHeroFigureIsSemiBoldAndNothingIsHeavier() {
    val heavy = styles.filterValues { it.fontWeight!! >= FontWeight.SemiBold }
    assertEquals(setOf("displaySmall"), heavy.keys)
    assertEquals(FontWeight.SemiBold, SadadTypography.displaySmall.fontWeight)
  }

  @Test
  fun everyStyleSetsTabularDigits() {
    styles.forEach { (name, style) -> assertEquals(name, "tnum", style.fontFeatureSettings) }
  }

  @Test
  fun theScaleHasSixSizesAndNoneUnder12sp() {
    val sizes = styles.values.map { it.fontSize.value }.toSet()
    assertEquals(setOf(32f, 24f, 20f, 16f, 14f, 12f), sizes)
  }

  @Test
  fun everyCornerIsOnThe4dpGrid() {
    val shapes =
        with(SadadShapes) {
          mapOf(
              "extraSmall" to extraSmall,
              "small" to small,
              "medium" to medium,
              "large" to large,
              "extraLarge" to extraLarge,
          )
        }
    shapes.forEach { (name, shape) ->
      val corner = (shape as CornerBasedShape).topStart.toPx(Size(100f, 100f), Density(1f))
      assertEquals(name, 0f, corner % 4f)
    }
  }

  @Test
  fun darkSurfacesAreNearGrey() {
    val surfaces =
        with(SadadDarkPalette) {
          mapOf("bg" to bg, "surface" to surface, "card" to card, "soft" to soft, "line" to line)
        }
    surfaces.forEach { (name, color) ->
      assertTrue("$name spreads ${spread(color)}", spread(color) <= 0x0C)
    }
  }

  /** The gap between the colour's strongest and weakest channel, out of 255. */
  private fun spread(color: Color): Int {
    val argb = color.toArgb()
    val channels = listOf(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)
    return channels.max() - channels.min()
  }
}
