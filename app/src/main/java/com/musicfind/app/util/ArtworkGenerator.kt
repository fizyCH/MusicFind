package com.musicfind.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.LruCache
import java.io.ByteArrayOutputStream

/**
 * Generates a simple fallback artwork (two letters on a solid background) for
 * tracks that have no cover image, so the Android media player/lockscreen can
 * show something instead of a blank placeholder.
 */
object ArtworkGenerator {

    private val cache = LruCache<String, ByteArray>(24)

    fun generate(text: String, background: Int, foreground: Int, size: Int = 512): ByteArray? {
        val key = "$text|$background|$foreground|$size"
        cache.get(key)?.let { return it }
        return try {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(background)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = foreground
                textSize = size * 0.40f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val cx = size / 2f
            val cy = size / 2f - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(text, cx, cy, paint)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            val bytes = out.toByteArray()
            cache.put(key, bytes)
            bytes
        } catch (_: Throwable) {
            null
        }
    }
}
