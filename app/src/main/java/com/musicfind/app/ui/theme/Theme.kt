package com.musicfind.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

data class MfColors(
    val isLight: Boolean,
    val accent: Color,
) {
    val background: Color get() = if (isLight) MfPalette.LightBackground else MfPalette.DarkBackground
    val background2: Color get() = if (isLight) MfPalette.LightBackground2 else MfPalette.DarkBackground2
    val surface: Color get() = if (isLight) MfPalette.LightSurface else MfPalette.Surface
    val text: Color get() = if (isLight) MfPalette.LightText else MfPalette.Text
    val muted: Color get() = if (isLight) MfPalette.LightMuted else MfPalette.Muted
    val error: Color get() = MfPalette.Error

    /** Solid card color for the mini player, visibly distinct from the app background. */
    val island: Color get() = if (isLight) Color(0xFFFFFFFF) else Color(0xFF161D2A)

    val glass: Color get() = if (isLight) Color(0xCCFFFFFF) else Color(0x14FFFFFF)
    val glassStrong: Color get() = if (isLight) Color(0xF2FFFFFF) else Color(0x1FFFFFFF)
    val glassBorder: Color get() = if (isLight) Color(0x22000000) else Color(0x1AFFFFFF)
    val accentSoft: Color get() = accent.copy(alpha = if (isLight) 0.18f else 0.14f)

    val screenBrush: Brush
        get() = if (isLight) {
            Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.12f), Color.Transparent),
            )
        } else {
            Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.20f), Color.Transparent),
            )
        }
}

val LocalMfColors = staticCompositionLocalOf {
    MfColors(isLight = false, accent = MfPalette.DefaultAccent)
}

@Composable
fun MusicFindTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: Color = MfPalette.DefaultAccent,
    content: @Composable () -> Unit,
) {
    val isLight = !darkTheme
    val mfColors = MfColors(isLight = isLight, accent = accent)

    val scheme = if (isLight) {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            background = MfPalette.LightBackground,
            onBackground = MfPalette.LightText,
            surface = MfPalette.LightSurface,
            onSurface = MfPalette.LightText,
            surfaceVariant = Color(0xFFE2E8F0),
            onSurfaceVariant = MfPalette.LightMuted,
            error = MfPalette.Error,
        )
    } else {
        darkColorScheme(
            primary = accent,
            onPrimary = Color(0xFF04121B),
            background = MfPalette.DarkBackground,
            onBackground = MfPalette.Text,
            surface = MfPalette.Surface,
            onSurface = MfPalette.Text,
            surfaceVariant = MfPalette.SurfaceAlt,
            onSurfaceVariant = MfPalette.Muted,
            error = MfPalette.Error,
        )
    }

    CompositionLocalProvider(LocalMfColors provides mfColors) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
