package com.moblized.parkpal.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.moblized.parkpal.data.ParkingSession

/** Hands directions off to Google Maps (or any maps app). */
object Nav {
    fun intent(lat: Double, lon: Double, walking: Boolean): Intent {
        val mode = if (walking) "w" else "d"
        return Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Format.latLon(lat, lon)}&mode=$mode"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun navigate(context: Context, lat: Double, lon: Double, walking: Boolean) {
        try {
            context.startActivity(intent(lat, lon, walking))
        } catch (e: ActivityNotFoundException) {
            val ll = Format.latLon(lat, lon)
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("geo:$ll?q=$ll")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (_: ActivityNotFoundException) {
            }
        }
    }

    fun share(context: Context, s: ParkingSession) {
        val text = buildString {
            append("I'm parked here: https://maps.google.com/?q=").append(Format.latLon(s.lat, s.lon))
            s.address?.let { append("\n").append(it) }
            if (s.level.isNotBlank()) append("\nLevel/space: ").append(s.level)
            if (s.note.isNotBlank()) append("\n").append(s.note)
        }
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Share parking spot").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
