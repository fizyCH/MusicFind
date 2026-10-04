package com.musicfind.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.musicfind.app.ui.theme.LocalMfColors

@Composable
fun MfSeekBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color? = null,
) {
    val colors = LocalMfColors.current
    val accent = accentColor ?: colors.accent
    val progress = value.coerceIn(0f, 1f)
    val density = LocalDensity.current
    val thumbPx = with(density) { 14.dp.toPx() }
    val trackHeightPx = with(density) { 3.dp.toPx() }

    fun positionToProgress(x: Float, width: Float): Float {
        val start = thumbPx / 2f
        val end = width - thumbPx / 2f
        val usable = (end - start).coerceAtLeast(1f)
        return ((x - start) / usable).coerceIn(0f, 1f)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onValueChange(positionToProgress(offset.x, size.width.toFloat()))
                    onValueChangeFinished()
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        onValueChange(positionToProgress(offset.x, size.width.toFloat()))
                    },
                    onDragEnd = { onValueChangeFinished() },
                    onDragCancel = { onValueChangeFinished() },
                    onHorizontalDrag = { change, _ ->
                        onValueChange(positionToProgress(change.position.x, size.width.toFloat()))
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val start = thumbPx / 2f
            val end = width - thumbPx / 2f
            val centerX = start + (end - start) * progress
            val centerY = height / 2f
            val radius = trackHeightPx / 2f

            drawRoundRect(
                color = colors.glassBorder,
                topLeft = Offset(start, centerY - radius),
                size = Size((end - start).coerceAtLeast(0f), trackHeightPx),
                cornerRadius = CornerRadius(radius, radius),
            )
            drawRoundRect(
                color = accent,
                topLeft = Offset(start, centerY - radius),
                size = Size(((centerX - start).coerceAtLeast(0f)), trackHeightPx),
                cornerRadius = CornerRadius(radius, radius),
            )
            drawCircle(
                color = accent,
                radius = thumbPx / 2f,
                center = Offset(centerX, centerY),
            )
        }
    }
}
