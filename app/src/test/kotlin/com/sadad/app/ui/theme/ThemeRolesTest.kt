package com.sadad.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Material 3 roles of ticket 201, light and dark: pinned values and the contrast rules. */
class ThemeRolesTest {
  private val light = colorsFor(SadadLightPalette, dark = false)
  private val dark = colorsFor(SadadDarkPalette, dark = true)
  private val schemes = mapOf("light" to light, "dark" to dark)

  @Test
  fun theLightRolesKeepTheirValues() {
    with(light) {
      assertEquals(Color(0xFF064B42), primary)
      assertEquals(Color(0xFFB5E6D2), primaryContainer)
      assertEquals(Color(0xFF159D89), secondary)
      assertEquals(Color(0xFF00201A), onSecondary)
      assertEquals(Color(0xFFD6EFE4), secondaryContainer)
      assertEquals(Color(0xFFFFF1DD), tertiaryContainer)
      assertEquals(Color(0xFFF5F8F3), surface)
      assertEquals(Color(0xFF142C24), onSurface)
      assertEquals(Color(0xFF586C60), onSurfaceVariant)
      assertEquals(Color.White, surfaceContainerLowest)
      assertEquals(Color(0xFFEEF6F1), surfaceContainerLow)
      assertEquals(Color(0xFFE7F3ED), surfaceContainer)
      assertEquals(Color(0xFFDEF1E8), surfaceContainerHigh)
      assertEquals(Color(0xFFD6EFE4), surfaceContainerHighest)
      assertEquals(Color(0xFF6E857A), outline)
      assertEquals(Color(0xFFD5E5D9), outlineVariant)
      assertEquals(Color(0xFFAB323A), error)
    }
  }

  @Test
  fun theDarkRolesKeepTheirValues() {
    with(dark) {
      assertEquals(Color(0xFF256C5A), primary)
      assertEquals(Color(0xFF1F4D43), primaryContainer)
      assertEquals(Color(0xFF78DEC0), secondary)
      assertEquals(Color(0xFF2E3833), secondaryContainer)
      assertEquals(Color(0xFF4A3A22), tertiaryContainer)
      assertEquals(Color(0xFF121514), surface)
      assertEquals(Color(0xFFEDF1EE), onSurface)
      assertEquals(Color(0xFF1A1E1C), surfaceContainerLowest)
      assertEquals(Color(0xFF1D2220), surfaceContainerLow)
      assertEquals(Color(0xFF202624), surfaceContainer)
      assertEquals(Color(0xFF242B28), surfaceContainerHigh)
      assertEquals(Color(0xFF29302D), surfaceContainerHighest)
      assertEquals(Color(0xFF75827C), outline)
      assertEquals(Color(0xFF2E3532), outlineVariant)
      assertEquals(Color(0xFF064B42), inversePrimary)
      assertEquals(Color(0xFFF59A9F), error)
    }
  }

  @Test
  fun theBrandRolesKeepTheirValues() {
    val (l, d) = SadadLightPalette to SadadDarkPalette
    assertEquals(Color(0xFFAB323A), l.owes)
    assertEquals(Color(0xFF9A5A13), l.credit)
    assertEquals(Color(0xFFF59A9F), d.owes)
    assertEquals(Color(0xFFFFD08A), d.credit)
    assertEquals(Color(0xFFC13634), l.badge)
  }

  @Test
  fun theOutlineIsAtLeast3To1OnEverySurface() {
    schemes.forEach { (name, c) ->
      steps(c).plus("surface" to c.surface).forEach { (step, color) ->
        val ratio = contrast(c.outline, color)
        assertTrue("$name outline on $step is $ratio", ratio >= 3f)
      }
    }
  }

  @Test
  fun theSurfaceContainerStepsAreDistinctAndOrdered() {
    schemes.forEach { (name, c) ->
      val lum = steps(c).map { it.second.luminance() }
      assertEquals("$name steps repeat", lum.size, lum.toSet().size)
      val ordered = if (name == "dark") lum == lum.sorted() else lum == lum.sortedDescending()
      assertTrue("$name steps are out of order: $lum", ordered)
    }
  }

  @Test
  fun theThreeContainersAreDistinct() {
    schemes.forEach { (name, c) ->
      val containers = setOf(c.primaryContainer, c.secondaryContainer, c.tertiaryContainer)
      assertEquals("$name containers repeat", 3, containers.size)
    }
  }

  @Test
  fun everyOnColourPairIsAtLeast4p5To1() {
    schemes.forEach { (name, c) ->
      pairs(c).forEach { (pair, colors) ->
        val ratio = contrast(colors.first, colors.second)
        assertTrue("$name $pair is $ratio", ratio >= 4.5f)
      }
    }
  }

  private fun steps(c: ColorScheme) =
      listOf(
          "surfaceContainerLowest" to c.surfaceContainerLowest,
          "surfaceContainerLow" to c.surfaceContainerLow,
          "surfaceContainer" to c.surfaceContainer,
          "surfaceContainerHigh" to c.surfaceContainerHigh,
          "surfaceContainerHighest" to c.surfaceContainerHighest,
      )

  private fun pairs(c: ColorScheme) =
      mapOf(
          "onPrimary/primary" to (c.onPrimary to c.primary),
          "onPrimaryContainer/primaryContainer" to (c.onPrimaryContainer to c.primaryContainer),
          "onSecondary/secondary" to (c.onSecondary to c.secondary),
          "onSecondaryContainer/secondaryContainer" to
              (c.onSecondaryContainer to c.secondaryContainer),
          "onTertiary/tertiary" to (c.onTertiary to c.tertiary),
          "onTertiaryContainer/tertiaryContainer" to (c.onTertiaryContainer to c.tertiaryContainer),
          "onError/error" to (c.onError to c.error),
          "onErrorContainer/errorContainer" to (c.onErrorContainer to c.errorContainer),
          "onBackground/background" to (c.onBackground to c.background),
          "onSurface/surface" to (c.onSurface to c.surface),
          "inverseOnSurface/inverseSurface" to (c.inverseOnSurface to c.inverseSurface),
          "inversePrimary/inverseSurface" to (c.inversePrimary to c.inverseSurface),
      ) +
          steps(c).flatMap { (step, color) ->
            listOf(
                "onSurface/$step" to (c.onSurface to color),
                "onSurfaceVariant/$step" to (c.onSurfaceVariant to color),
            )
          }

  private fun contrast(a: Color, b: Color): Float {
    val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
    return (hi + 0.05f) / (lo + 0.05f)
  }
}
