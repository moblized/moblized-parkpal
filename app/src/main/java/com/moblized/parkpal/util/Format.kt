package com.moblized.parkpal.util

import android.location.Location
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

object Format {
    fun time(ms: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))

    fun dateTime(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    fun duration(ms: Long): String {
        val mins = (ms / 60_000).coerceAtLeast(0)
        return when {
            mins < 60 -> "$mins min"
            mins % 60 == 0L -> "${mins / 60} h"
            else -> "${mins / 60} h ${mins % 60} min"
        }
    }

    fun ago(ms: Long): String = if (ms < 60_000) "just now" else "${duration(ms)} ago"

    fun countdown(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun ftIn(inches: Int): String = "${inches / 12}'${inches % 12}\""

    fun metersToFtIn(meters: Double): String = ftIn((meters / 0.0254).roundToInt())

    fun distance(meters: Double): String =
        if (meters < 160) "${(meters * 3.28084).roundToInt()} ft"
        else String.format(Locale.US, "%.1f mi", meters / 1609.344)

    fun latLon(lat: Double, lon: Double): String = String.format(Locale.US, "%.6f,%.6f", lat, lon)

    /** Walking minutes for a straight-line distance (about 3 mph, plus a little for corners). */
    fun walkMinutes(meters: Double): Int = max(1, ceil(meters * 1.2 / 80.0).toInt())

    fun walk(meters: Double): String = "${walkMinutes(meters)} min walk"

    fun driveTime(seconds: Double): String = duration(max(60_000L, (seconds * 1000).toLong()))
}

fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val result = FloatArray(1)
    Location.distanceBetween(lat1, lon1, lat2, lon2, result)
    return result[0].toDouble()
}
