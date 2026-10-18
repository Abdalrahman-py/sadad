package com.sadad.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The Java app's palette, as Material 3 roles. */
val SadadLightColors =
    lightColorScheme(
        primary = Color(0xFF064B42),
        onPrimary = Color.White,
        secondary = Color(0xFF159D89),
        onSecondary = Color.White,
        tertiary = Color(0xFF9A5A13),
        onTertiary = Color.White,
        background = Color(0xFFF5F8F3),
        onBackground = Color(0xFF142C24),
        surface = Color(0xFFE7F3ED),
        onSurface = Color(0xFF142C24),
        surfaceVariant = Color(0xFFD6EFE4),
        onSurfaceVariant = Color(0xFF65796C),
        outline = Color(0xFFD5E5D9),
    )

val SadadDarkColors =
    darkColorScheme(
        primary = Color(0xFF256C5A),
        onPrimary = Color.White,
        secondary = Color(0xFF78DEC0),
        onSecondary = Color(0xFF102721),
        tertiary = Color(0xFFFFD08A),
        onTertiary = Color(0xFF102721),
        background = Color(0xFF102721),
        onBackground = Color(0xFFF0F7EF),
        surface = Color(0xFF1C3931),
        onSurface = Color(0xFFF0F7EF),
        surfaceVariant = Color(0xFF2A4E42),
        onSurfaceVariant = Color(0xFFB5CEC0),
        outline = Color(0xFF375A4B),
    )

@Composable
fun SadadTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = if (dark) SadadDarkColors else SadadLightColors, content = content)
}
