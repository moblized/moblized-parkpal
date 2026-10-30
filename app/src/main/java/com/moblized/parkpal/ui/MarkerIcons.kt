package com.moblized.parkpal.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
