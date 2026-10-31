package com.moblized.parkpal.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.toArgb
import com.moblized.parkpal.data.FitStatus
import com.moblized.parkpal.data.ParkingPlace
import com.moblized.parkpal.ui.theme.ParkColors

/** Draws the round "P" map markers, colored by price and height fit. */
class MarkerIcons(private val context: Context) {
    private val cache = HashMap<String, Drawable>()

    fun forPlace(place: ParkingPlace, status: FitStatus, selected: Boolean): Drawable {
        val color = statusColor(place, status).toArgb()
        val label = if (status == FitStatus.TOO_LOW) "!" else "P"
        return circle(color, label, if (selected) 40 else 30)
    }

    fun parked(): Drawable = circle(ParkColors.Parked.toArgb(), "🛻", 40)

    /** Numbered stop on the timeline map. */
    fun numbered(n: Int): Drawable = circle(0xFF0E7490.toInt(), n.toString(), 28)

    /** A round marker with an emoji, e.g. for gas stations or restaurants. */
    fun emoji(color: Int, emoji: String, selected: Boolean = false): Drawable = circle(color, emoji, if (selected) 40 else 32)

    /** Your position while navigating: a blue arrow pointing up (the marker rotates it). */
    fun navArrow(): Drawable = cache.getOrPut("navArrow") {
        val d = context.resources.displayMetrics.density
        val size = (44 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val s = size.toFloat()
        val path = Path().apply {
            moveTo(s * 0.5f, s * 0.08f)
            lineTo(s * 0.86f, s * 0.88f)
            lineTo(s * 0.5f, s * 0.68f)
            lineTo(s * 0.14f, s * 0.88f)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = 4f * d
        paint.color = android.graphics.Color.WHITE
        paint.setShadowLayer(3f * d, 0f, 1f * d, 0x66000000)
        canvas.drawPath(path, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
        paint.color = 0xFF1A73E8.toInt()
        canvas.drawPath(path, paint)
        BitmapDrawable(context.resources, bmp)
    }

    /** A red map pin for the destination, anchored at its tip. */
    fun destination(): Drawable = cache.getOrPut("destination") {
        val d = context.resources.displayMetrics.density
        val w = (34 * d).toInt()
        val h = (46 * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = w / 2f
        val path = Path().apply {
            addCircle(r, r, r - 2.5f * d, Path.Direction.CW)
            moveTo(r - r * 0.62f, r + r * 0.55f)
            lineTo(r, h - 1.5f * d)
            lineTo(r + r * 0.62f, r + r * 0.55f)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * d
        paint.color = android.graphics.Color.WHITE
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.color = ParkColors.Destination.toArgb()
        canvas.drawPath(path, paint)
        paint.color = android.graphics.Color.WHITE
        canvas.drawCircle(r, r, r * 0.34f, paint)
        BitmapDrawable(context.resources, bmp)
    }

    private fun circle(color: Int, label: String, sizeDp: Int): Drawable =
        cache.getOrPut("$color|$label|$sizeDp") {
            val density = context.resources.displayMetrics.density
            val size = (sizeDp * density).toInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val r = size / 2f
            paint.color = android.graphics.Color.WHITE
            canvas.drawCircle(r, r, r, paint)
            paint.color = color
            canvas.drawCircle(r, r, r - 2.5f * density, paint)
            paint.color = android.graphics.Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = size * 0.5f
            val y = r - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(label, r, y, paint)
            BitmapDrawable(context.resources, bmp)
        }
}
