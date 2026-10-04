package com.musicfind.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * Rolling bar visualizer driven by recent microphone amplitudes (0..1).
 */
@Composable
fun MicVisualizer(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
    barCount: Int = 40,
    color: Color = Color(0xFF22C55E),
) {
    val recent = amplitudes.takeLast(barCount)
    val bars = List((barCount - recent.size).coerceAtLeast(0)) { 0f } + recent
    Canvas(modifier) {
        val count = bars.size.coerceAtLeast(1)
        val slot = size.width / count
        val barWidth = (slot * 0.6f).coerceAtLeast(1f)
        val gap = slot - barWidth
        bars.forEachIndexed { index, value ->
            val level = value.coerceIn(0f, 1f)
            val height = (size.height * (0.08f + 0.92f * level)).coerceAtLeast(barWidth)
            val left = index * slot + gap / 2f
            val top = (size.height - height) / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
