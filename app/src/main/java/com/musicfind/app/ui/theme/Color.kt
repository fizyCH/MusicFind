package com.musicfind.app.ui.theme

import androidx.compose.ui.graphics.Color

object MfPalette {
    val DarkBackground = Color(0xFF05070B)
    val DarkBackground2 = Color(0xFF090D14)
    val Surface = Color(0xFF0B1220)
    val SurfaceAlt = Color(0xFF111827)
    val Muted = Color(0xFF94A3B8)
    val MutedDark = Color(0xFF64748B)
    val Text = Color(0xFFE2E8F0)
    val Warning = Color(0xFFFBBF24)
    val Error = Color(0xFFF43F5E)

    val LightBackground = Color(0xFFEEF6F5)
    val LightBackground2 = Color(0xFFDDE8EF)
    val LightSurface = Color(0xFFFFFFFF)
    val LightText = Color(0xFF0F172A)
    val LightMuted = Color(0xFF475569)

    val DefaultAccent = Color(0xFF22C55E)
}

fun parseHexColor(value: String?, fallback: Color = MfPalette.DefaultAccent): Color {
    if (value.isNullOrBlank()) return fallback
    return try {
        val clean = value.trim().removePrefix("#")
        val hex = when (clean.length) {
            6 -> "FF$clean"
            8 -> clean
            else -> return fallback
        }
        Color(hex.toLong(16))
    } catch (_: Exception) {
        fallback
    }
}
