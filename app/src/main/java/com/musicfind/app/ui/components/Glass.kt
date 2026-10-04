package com.musicfind.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.musicfind.app.ui.theme.LocalMfColors

@Composable
fun GradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalMfColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .background(colors.screenBrush),
        content = content,
    )
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    strong: Boolean = false,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalMfColors.current
    val shape = RoundedCornerShape(24.dp)
    var base = modifier
        .clip(shape)
        .background(if (strong) colors.glassStrong else colors.glass)
        .border(1.dp, colors.glassBorder, shape)
    if (onClick != null) {
        base = base.clickable(onClick = onClick)
    }
    Column(modifier = base.padding(contentPadding), content = content)
}

@Composable
fun GlassRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalMfColors.current
    val shape = RoundedCornerShape(20.dp)
    var base = modifier
        .clip(shape)
        .background(colors.glass)
        .border(1.dp, colors.glassBorder, shape)
    if (onClick != null) base = base.clickable(onClick = onClick)
    Row(
        modifier = base.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun SectionTitle(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = LocalMfColors.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.text)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        trailing?.invoke()
    }
}

fun glassGradient(isLight: Boolean): Brush = if (isLight) {
    Brush.linearGradient(listOf(Color(0xE6FFFFFF), Color(0xB3FFFFFF)))
} else {
    Brush.linearGradient(listOf(Color(0x1FFFFFFF), Color(0x0AFFFFFF)))
}

@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 24.dp,
    elevation: androidx.compose.ui.unit.Dp = 0.dp,
    backgroundColor: Color? = null,
    backdrop: BackdropState? = null,
    blurRadius: androidx.compose.ui.unit.Dp = 26.dp,
    frosted: Boolean = true,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalMfColors.current
    val shape = RoundedCornerShape(cornerRadius)
    val blurPx = with(LocalDensity.current) { blurRadius.toPx() }
    val blurEnabled = frosted && backdrop != null && android.os.Build.VERSION.SDK_INT >= 31
    val surfacePosition = remember { mutableStateOf(Offset.Zero) }
    // Uniform (not fading to transparent) fill: a vertical fade made the bottom of
    // the surface show the dark app background through it, which looked like a
    // black backdrop behind the player.
    val baseGradient = Brush.verticalGradient(
        colors = if (colors.isLight) {
            listOf(Color(0x99FFFFFF), Color(0x73FFFFFF))
        } else {
            listOf(Color(0x33FFFFFF), Color(0x1AFFFFFF))
        },
    )
    val gloss = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = if (colors.isLight) 0.30f else 0.10f),
            Color.Transparent,
            Color.White.copy(alpha = if (colors.isLight) 0.10f else 0.03f),
        ),
    )
    // Crisp top-left specular highlight for the "liquid glass" look.
    val specular = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = if (colors.isLight) 0.55f else 0.20f),
            Color.Transparent,
        ),
        start = Offset.Zero,
        end = Offset(460f, 280f),
    )
    // Never use Modifier.shadow here: a drop shadow bleeds outside the shape
    // (there is no way to clip it) and shows up as a dark rectangle next to the
    // surface. Everything is clipped strictly to the surface shape instead.
    val base = modifier
        .clip(shape)
        .onGloballyPositioned { coordinates -> surfacePosition.value = coordinates.positionInRoot() }
        .then(if (backgroundColor != null) Modifier.background(backgroundColor) else Modifier)
    Box(modifier = base) {
        if (blurEnabled) {
            val state = backdrop!!
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                    }
                    .drawWithContent {
                        val dx = state.sourcePosition.x - surfacePosition.value.x
                        val dy = state.sourcePosition.y - surfacePosition.value.y
                        translate(-dx, -dy) {
                            drawLayer(state.layer)
                        }
                    },
            )
        }
        if (frosted) {
            Box(Modifier.matchParentSize().background(baseGradient))
            Box(Modifier.matchParentSize().background(gloss))
            Box(Modifier.matchParentSize().background(specular))
        }
        Box(Modifier.matchParentSize().border(1.dp, colors.glassBorder, shape))
        Column(Modifier.padding(contentPadding), content = content)
    }
}
