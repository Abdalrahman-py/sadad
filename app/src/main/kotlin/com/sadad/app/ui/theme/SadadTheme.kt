package com.sadad.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.unit.TextUnit
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
        bg = Color(0xFF121514),
        surface = Color(0xFF1A1E1C),
        soft = Color(0xFF252B28),
        primary = Color(0xFF256C5A),
        accent = Color(0xFF78DEC0),
        text = Color(0xFFEDF1EE),
        muted = Color(0xFFA3ADA8),
        line = Color(0xFF2E3532),
        warn = Color(0xFFFFD08A),
        card = Color(0xFF1A1E1C),
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
@Deprecated(
    "Read MaterialTheme.colorScheme for common roles and sadadBrand for the brand ones; " +
        "the mapping is in docs/ui-theme.md. Ticket 208 removes the last reads."
)
val sadadPalette: SadadPalette
  @Composable get() = LocalSadadPalette.current

/**
 * The brand roles Material 3 has no slot for. Everything else lives in `MaterialTheme.colorScheme`;
 * docs/ui-theme.md maps every palette field to its role.
 */
@Immutable
data class SadadBrand(
    val isDark: Boolean,
    /** Text-weight actions and links: `primary` in light, `secondary` (the accent) in dark. */
    val link: Color,
    /** «عليه»: the person owes the store. */
    val owes: Color,
    val owesSoft: Color,
    /** «له»: the store holds the person's money. */
    val credit: Color,
    val creditSoft: Color,
    /** Debts and the destructive actions. */
    val debt: Color,
    val debtSoft: Color,
    /** The hero card runs diagonally from `heroStart` to `heroEnd`. */
    val heroStart: Color,
    val heroEnd: Color,
    val onHero: Color,
    val onHeroMuted: Color,
    val badge: Color,
    /** The bottom-bar sync dot: entries wait to sync, or sync is offline or stuck. */
    val syncPending: Color,
    val syncOffline: Color,
)

internal fun brandFor(p: SadadPalette) =
    SadadBrand(
        isDark = p.isDark,
        link = if (p.isDark) p.accent else p.primary,
        owes = p.owes,
        owesSoft = p.owesSoft,
        credit = p.credit,
        creditSoft = p.creditSoft,
        debt = p.debt,
        debtSoft = p.debtSoft,
        heroStart = p.primary,
        heroEnd = p.heroEnd,
        onHero = Color.White,
        onHeroMuted = p.onHeroMuted,
        badge = p.badge,
        syncPending = Color(0xFFE0A23A),
        syncOffline = Color(0xFFC13634),
    )

val LocalSadadBrand = staticCompositionLocalOf { brandFor(SadadLightPalette) }

/** The brand roles of the current theme. */
val sadadBrand: SadadBrand
  @Composable get() = LocalSadadBrand.current

/*
 * Every role is set on purpose (docs/ui-theme.md has the contrast of each pair):
 * - `outline` is at least 3:1 on `surface` and every container step, for field and switch borders;
 *   `outlineVariant` stays the light `line` for dividers.
 * - The container steps run Lowest (cards) → Highest in brand greens, distinct in both themes.
 * - The three containers differ, and every on-colour pair is at least 4.5:1.
 */
internal fun colorsFor(p: SadadPalette, dark: Boolean) =
    if (dark)
        darkColorScheme(
            primary = p.primary,
            onPrimary = Color.White,
            primaryContainer = Color(0xFF1F4D43),
            onPrimaryContainer = p.onHeroMuted,
            secondary = p.accent,
            onSecondary = p.bg,
            secondaryContainer = Color(0xFF2E3833),
            onSecondaryContainer = p.text,
            tertiary = p.warn,
            onTertiary = p.bg,
            tertiaryContainer = p.creditSoft,
            onTertiaryContainer = p.warn,
            background = p.bg,
            onBackground = p.text,
            surface = p.bg,
            onSurface = p.text,
            surfaceVariant = p.surface,
            onSurfaceVariant = p.muted,
            surfaceTint = Color.Transparent,
            surfaceBright = p.soft,
            surfaceDim = p.bg,
            surfaceContainerLowest = p.card,
            surfaceContainerLow = Color(0xFF1C201E),
            surfaceContainer = Color(0xFF1F2321),
            surfaceContainerHigh = Color(0xFF222826),
            surfaceContainerHighest = p.soft,
            inverseSurface = p.text,
            inverseOnSurface = p.bg,
            // The snackbar action sits on the light inverse surface.
            inversePrimary = SadadLightPalette.primary,
            outline = Color(0xFF75827C),
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
            primaryContainer = Color(0xFFB5E6D2),
            onPrimaryContainer = p.primary,
            secondary = p.accent,
            // White on the accent is 3.38:1; this deep green is 5.09:1.
            onSecondary = Color(0xFF00201A),
            // A step darker than the bars, so the selected tab and chips stand out on them.
            secondaryContainer = Color(0xFFC3E4D5),
            onSecondaryContainer = p.primary,
            tertiary = p.warn,
            onTertiary = Color.White,
            tertiaryContainer = p.creditSoft,
            onTertiaryContainer = p.warn,
            background = p.bg,
            onBackground = p.text,
            surface = p.bg,
            onSurface = p.text,
            surfaceVariant = p.surface,
            // 4.5:1 or more on every container step, the darker bar step included.
            onSurfaceVariant = Color(0xFF51655A),
            surfaceTint = Color.Transparent,
            surfaceBright = Color.White,
            surfaceDim = p.soft,
            surfaceContainerLowest = p.card,
            surfaceContainerLow = Color(0xFFEEF6F1),
            surfaceContainer = p.surface,
            // The top and bottom bars: a clear step below the page, not a near-match.
            surfaceContainerHigh = Color(0xFFD9E9E0),
            // A step below the bars, so the steps stay ordered.
            surfaceContainerHighest = Color(0xFFD1E4D9),
            inverseSurface = p.text,
            inverseOnSurface = p.bg,
            // The snackbar action and the toast mark on the dark inverse surface.
            inversePrimary = Color(0xFF78DEC0),
            // 3:1 or more on every container step.
            outline = Color(0xFF667D72),
            outlineVariant = p.line,
            error = p.debt,
            onError = Color.White,
            errorContainer = p.debtSoft,
            onErrorContainer = p.debt,
            scrim = Color.Black,
        )

/** The radii, all on the 4dp grid: 12dp buttons, fields and cards, 16dp blocks, 24dp sheets. */
val SadadShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        fontFeatureSettings = "tnum",
    )

/**
 * One scale in the system face, six sizes and two weights, every style in tabular digits: 32 the
 * hero figure, the only SemiBold; 24 page titles, 20 card figures and 16 section titles, Medium; 16
 * fields and body, 14 body and buttons, 12 notes, captions and the bottom bar.
 */
val SadadTypography =
    Typography(
        displaySmall = style(32, 40, FontWeight.SemiBold),
        headlineLarge = style(24, 32, FontWeight.Medium),
        headlineMedium = style(24, 32, FontWeight.Medium),
        headlineSmall = style(24, 32, FontWeight.Medium),
        titleLarge = style(20, 28, FontWeight.Medium),
        titleMedium = style(16, 24, FontWeight.Medium),
        titleSmall = style(14, 20, FontWeight.Medium),
        bodyLarge = style(16, 24),
        bodyMedium = style(14, 20),
        bodySmall = style(12, 16),
        labelLarge = style(14, 20, FontWeight.Medium),
        labelMedium = style(12, 16, FontWeight.Medium),
        labelSmall = style(12, 16),
    )

@Composable
fun SadadTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  val palette = if (dark) SadadDarkPalette else SadadLightPalette
  CompositionLocalProvider(
      LocalSadadPalette provides palette,
      LocalSadadBrand provides brandFor(palette),
  ) {
    MaterialTheme(
        colorScheme = colorsFor(palette, dark),
        shapes = SadadShapes,
        typography = SadadTypography,
    ) {
      // A Text that sets only its size keeps a line height of that size, not bodyLarge's 24sp,
      // which at the largest font is 48dp and overflows the 42dp and 44dp boxes such text sits in.
      val inherited = MaterialTheme.typography.bodyLarge.copy(lineHeight = TextUnit.Unspecified)
      CompositionLocalProvider(LocalTextStyle provides inherited, content = content)
    }
  }
}
