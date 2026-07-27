package com.spiritwisestudios.gpstracker.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.createBitmap
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory

/**
 * Renders the colored pin bitmaps for map markers. MapLibre markers take a
 * bitmap icon rather than the hue/alpha parameters the Google markers used,
 * so [MarkerStyling]'s category hue and visited fade are baked into the
 * bitmap here.
 */
object MarkerIcons {

    private val cache = mutableMapOf<String, Icon>()

    /** A teardrop pin in the given hue; alpha fades visited places. */
    fun pin(context: Context, hue: Float, alpha: Float = 1f): Icon {
        val key = "$hue/$alpha"
        return cache.getOrPut(key) {
            IconFactory.getInstance(context).fromBitmap(pinBitmap(context, hue, alpha))
        }
    }

    private fun pinBitmap(context: Context, hue: Float, alpha: Float): Bitmap {
        val density = context.resources.displayMetrics.density
        val width = (26 * density).toInt()
        val height = (38 * density).toInt()
        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)

        val alphaChannel = (alpha.coerceIn(0f, 1f) * 255).toInt()
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.HSVToColor(alphaChannel, floatArrayOf(hue, 0.8f, 0.85f))
        }

        val cx = width / 2f
        val cy = width / 2f
        val radius = width / 2f - density

        // Teardrop: circular head plus a tail down to the anchor point
        val tail = Path().apply {
            moveTo(cx - radius * 0.6f, cy + radius * 0.5f)
            lineTo(cx, height - density)
            lineTo(cx + radius * 0.6f, cy + radius * 0.5f)
            close()
        }
        canvas.drawPath(tail, bodyPaint)
        canvas.drawCircle(cx, cy, radius, bodyPaint)

        val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alphaChannel, 255, 255, 255)
        }
        canvas.drawCircle(cx, cy, radius * 0.38f, holePaint)

        return bitmap
    }
}
