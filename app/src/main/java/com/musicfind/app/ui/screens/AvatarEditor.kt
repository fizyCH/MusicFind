package com.musicfind.app.ui.screens

import android.graphics.Bitmap
import com.musicfind.app.R
import com.musicfind.app.util.Loc
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.musicfind.app.ui.theme.LocalMfColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

private const val PREVIEW_SIZE = 260
private const val OUTPUT_SIZE = 512

/** Simple avatar editor: choose the visible square with drag & pinch before uploading. */
@Composable
fun AvatarEditorDialog(
    uri: Uri,
    onDismiss: () -> Unit,
    onSave: (File) -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalMfColors.current
    val density = LocalDensity.current
    val previewPx = with(density) { PREVIEW_SIZE.dp.toPx() }

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) { loadScaledBitmap(context, uri, 1200) }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(Loc.s(R.string.avatar)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(PREVIEW_SIZE.dp)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .pointerInput(bitmap) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val bmp = bitmap ?: return@detectTransformGestures
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                val cover = max(previewPx / bmp.width, previewPx / bmp.height) * scale
                                val maxX = ((bmp.width * cover) - previewPx) / 2f
                                val maxY = ((bmp.height * cover) - previewPx) / 2f
                                offset = Offset(
                                    (offset.x + pan.x).coerceIn(-maxX.coerceAtLeast(0f), maxX.coerceAtLeast(0f)),
                                    (offset.y + pan.y).coerceIn(-maxY.coerceAtLeast(0f), maxY.coerceAtLeast(0f)),
                                )
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = bitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                },
                        )
                    } else {
                        CircularProgressIndicator(color = colors.accent)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    Loc.s(R.string.avatar_editor_hint),
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = bitmap != null && !saving,
                onClick = {
                    val bmp = bitmap ?: return@TextButton
                    saving = true
                    val baseCover = max(previewPx / bmp.width, previewPx / bmp.height)
                    val result = cropToSquare(bmp, baseCover, scale, offset, previewPx)
                    val file = File(context.cacheDir, "avatar_${System.currentTimeMillis()}.jpg")
                    file.outputStream().use { result.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    result.recycle()
                    onSave(file)
                },
            ) { Text(Loc.s(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(Loc.s(R.string.cancel)) }
        },
    )
}

private fun cropToSquare(
    source: Bitmap,
    baseCover: Float,
    scale: Float,
    offset: Offset,
    previewPx: Float,
): Bitmap {
    val result = Bitmap.createBitmap(OUTPUT_SIZE, OUTPUT_SIZE, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(result)
    canvas.drawColor(android.graphics.Color.BLACK)
    val cover = baseCover * scale
    val ratio = OUTPUT_SIZE / previewPx
    val matrix = Matrix()
    matrix.setScale(cover, cover)
    matrix.postTranslate(
        previewPx / 2f + offset.x - source.width * cover / 2f,
        previewPx / 2f + offset.y - source.height * cover / 2f,
    )
    matrix.postScale(ratio, ratio)
    canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
    return result
}

private fun loadScaledBitmap(context: android.content.Context, uri: Uri, maxSize: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSize || bounds.outHeight / sample > maxSize) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    } catch (_: Exception) {
        null
    }
}
