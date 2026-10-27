package com.sadad.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Java app's palette (`NativeScreens.java` at tag `pre-kotlin-app-2026-10`): bg, surface, soft,
 * primary, accent, text, muted, line and warn, plus the few colours its views set inline. Every
 * Material role is filled so no baseline (purple) role shows through.
 */
@Immutable
data class SadadPalette(
    val bg: Color,
    /** The page background runs from [bg] to this, top to bottom. */
    val bgEnd: Color,
    val surface: Color,
    val soft: Color,
    val primary: Color,
    val accent: Color,
    val text: Color,
    val muted: Color,
    val line: Color,
    val warn: Color,
    /**
     * Cards that sit on the page (month metrics, movement rows): white in light, surface in dark.
     */
    val card: Color,
    /** The hero card runs diagonally from [primary] to this. */
    val heroEnd: Color,
    val onHeroMuted: Color,
    /** Debts and the destructive actions. */
    val debt: Color,
    val debtSoft: Color,
    val badge: Color,
    /** «عليه»: the person owes the store. */
    val owes: Color,
    val owesSoft: Color,
    /** «له»: the store holds the person's money. */
    val credit: Color,
    val creditSoft: Color,
) {
  val isDark: Boolean
    get() = bg.luminance() < 0.5f
}

val SadadLightPalette =
    SadadPalette(
        bg = Color(0xFFF5F8F3),
        bgEnd = Color(0xFFEDF6EF),
        surface = Color(0xFFE7F3ED),
        soft = Color(0xFFD6EFE4),
        primary = Color(0xFF064B42),
        accent = Color(0xFF159D89),
        text = Color(0xFF142C24),
        // 4.5:1 on the page; the Java app's #65796C fails.
        muted = Color(0xFF5B6F63),
        line = Color(0xFFD5E5D9),
        warn = Color(0xFF9A5A13),
        card = Color.White,
        heroEnd = Color(0xFF02302B),
        onHeroMuted = Color(0xFFC6F0E0),
        debt = Color(0xFFAB323A),
        debtSoft = Color(0xFFFFE1E1),
        badge = Color(0xFFC13634),
        owes = Color(0xFFAB323A),
        owesSoft = Color(0xFFFFE1E1),
        credit = Color(0xFF9A5A13),
        creditSoft = Color(0xFFFFF1DD),
    )

val SadadDarkPalette =
    SadadPalette(
        bg = Color(0xFF102721),
        bgEnd = Color(0xFF142E25),
        surface = Color(0xFF1C3931),
        soft = Color(0xFF2A4E42),
        primary = Color(0xFF256C5A),
        accent = Color(0xFF78DEC0),
        text = Color(0xFFF0F7EF),
        muted = Color(0xFFB5CEC0),
        line = Color(0xFF375A4B),
        warn = Color(0xFFFFD08A),
        card = Color(0xFF1C3931),
        heroEnd = Color(0xFF02302B),
        onHeroMuted = Color(0xFFC6F0E0),
        debt = Color(0xFFF59A9F),
        debtSoft = Color(0xFF583B3D),
        badge = Color(0xFFC13634),
        owes = Color(0xFFF59A9F),
        owesSoft = Color(0xFF583B3D),
        credit = Color(0xFFFFD08A),
        creditSoft = Color(0xFF4A3A22),
    )

val LocalSadadPalette = staticCompositionLocalOf { SadadLightPalette }

/** The palette of the current theme, for the brand components the Material roles do not cover. */
val sadadPalette: SadadPalette
  @Composable get() = LocalSadadPalette.current

private fun colorsFor(p: SadadPalette, dark: Boolean) =
    if (dark)
        darkColorScheme(
            primary = p.primary,
            onPrimary = Color.White,
            primaryContainer = p.soft,
            onPrimaryContainer = p.text,
            secondary = p.accent,
            onSecondary = p.bg,
            secondaryContainer = p.soft,
            onSecondaryContainer = p.text,
            tertiary = p.warn,
            onTertiary = p.bg,
            tertiaryContainer = p.soft,
            onTertiaryContainer = p.warn,
            background = p.bg,
            onBackground = p.text,
            surface = p.bg,
            onSurface = p.text,
            surfaceVariant = p.surface,
            onSurfaceVariant = p.muted,
            surfaceTint = Color.Transparent,
            surfaceBright = p.surface,
            surfaceDim = p.bg,
            surfaceContainerLowest = p.bg,
            surfaceContainerLow = p.surface,
            surfaceContainer = p.surface,
            surfaceContainerHigh = p.surface,
            surfaceContainerHighest = p.soft,
            inverseSurface = p.text,
            inverseOnSurface = p.bg,
            inversePrimary = p.accent,
            outline = p.line,
            outlineVariant = p.line,
            error = p.debt,
            onError = p.bg,
            errorContainer = p.debtSoft,
            onErrorContainer = p.text,
            scrim = Color.Black,
        )
    else
        lightColorScheme(
            primary = p.primary,
            onPrimary = Color.White,
            primaryContainer = p.soft,
            onPrimaryContainer = p.primary,
            secondary = p.accent,
            onSecondary = Color.White,
            secondaryContainer = p.soft,
            onSecondaryContainer = p.primary,
            tertiary = p.warn,
            onTertiary = Color.White,
            tertiaryContainer = Color(0xFFFFF1DD),
            onTertiaryContainer = p.warn,
            background = p.bg,
            onBackground = p.text,
            surface = p.bg,
            onSurface = p.text,
            surfaceVariant = p.surface,
            onSurfaceVariant = p.muted,
            surfaceTint = Color.Transparent,
            surfaceBright = Color.White,
            surfaceDim = p.surface,
            surfaceContainerLowest = Color.White,
            surfaceContainerLow = p.surface,
            surfaceContainer = p.surface,
            // Dialogs and sheets are white, as the boards draw their sheets.
            surfaceContainerHigh = p.card,
            surfaceContainerHighest = p.soft,
            inverseSurface = p.text,
            inverseOnSurface = p.bg,
            inversePrimary = p.soft,
            outline = p.line,
            outlineVariant = p.line,
            error = p.debt,
            onError = Color.White,
            errorContainer = p.debtSoft,
            onErrorContainer = p.debt,
            scrim = Color.Black,
        )

/**
 * The Java app's radii: 10dp buttons and cards, 13dp fields, 14dp switch rows, 18dp the hero and
 * 26dp the sign-in panels.
 */
val SadadShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(13.dp),
        large = RoundedCornerShape(18.dp),
        extraLarge = RoundedCornerShape(26.dp),
    )

private fun style(size: Int, bold: Boolean = false, line: Int = (size * 1.4f).toInt()) =
    TextStyle(
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    )

/**
 * The Java app's sizes in the system face: 34 the hero figure, 23 page titles, 21 card figures, 17
 * section titles, 15 fields and body, 14 buttons, 13 field labels and subtitles, 12 notes, 11
 * captions and 10 the bottom bar.
 */
val SadadTypography =
    Typography(
        displaySmall = style(34, bold = true, line = 42),
        headlineLarge = style(26, bold = true),
        headlineMedium = style(23, bold = true),
        headlineSmall = style(23, bold = true),
        titleLarge = style(21, bold = true),
        titleMedium = style(17, bold = true),
        titleSmall = style(15, bold = true),
        bodyLarge = style(15),
        bodyMedium = style(13),
        bodySmall = style(12),
        labelLarge = style(14, bold = true),
        labelMedium = style(13, bold = true),
        labelSmall = style(10),
    )

@Composable
fun SadadTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  val palette = if (dark) SadadDarkPalette else SadadLightPalette
  CompositionLocalProvider(LocalSadadPalette provides palette) {
    MaterialTheme(
        colorScheme = colorsFor(palette, dark),
        shapes = SadadShapes,
        typography = SadadTypography,
        content = content,
    )
  }
}
