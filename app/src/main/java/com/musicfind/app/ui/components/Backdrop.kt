package com.musicfind.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

/**
 * Shared handle for a backdrop-blur surface.
 *
 * The content that should be blurred is recorded into [layer]; glass surfaces
 * then draw a translated, blurred copy of it. Self-contained, no third-party
 * dependency.
 */
class BackdropState internal constructor(
    internal val layer: GraphicsLayer,
) {
    internal var sourcePosition: Offset = Offset.Zero
}

@Composable
fun rememberBackdropState(): BackdropState {
    val layer = rememberGraphicsLayer()
    return remember(layer) { BackdropState(layer) }
}

/** Marks this composable as the content that glass surfaces blur. */
fun Modifier.backdropSource(state: BackdropState): Modifier =
    this
        .onGloballyPositioned { coordinates ->
            state.sourcePosition = coordinates.positionInRoot()
        }
        .drawWithContent {
            state.layer.record(androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())) {
                this@drawWithContent.drawContent()
            }
            drawLayer(state.layer)
        }
