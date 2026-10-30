package com.moblized.parkpal.data

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Place search (OpenStreetMap Nominatim) and street addresses for saved spots. */
object Geo {
    suspend fun search(query: String, nearLat: Double?, nearLon: Double?): List<GeoResult> =
        withContext(Dispatchers.IO) {
            val url = StringBuilder("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&q=")
                .append(URLEncoder.encode(query, "UTF-8"))
            if (nearLat != null && nearLon != null) {
                val box = String.format(
                    Locale.US, "%.4f,%.4f,%.4f,%.4f",
                    nearLon - 0.6, nearLat + 0.6, nearLon + 0.6, nearLat - 0.6,
                )
                url.append("&viewbox=").append(box)
            }
            val conn = URL(url.toString()).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("User-Agent", OverpassApi.USER_AGENT)
                if (conn.responseCode !in 200..299) throw IOException("Search failed (HTTP ${conn.responseCode})")
                val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val display = o.optString("display_name")
                    val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                    val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                    val title = o.optString("name").ifBlank { display.substringBefore(",") }
                    GeoResult(title = title, subtitle = display, lat = lat, lon = lon)
                }
            } finally {
                conn.disconnect()
            }
        }

    /** A short street address like "120 W Main St, Springfield", or null. */
    @Suppress("DEPRECATION")
    suspend fun reverse(context: Context, lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        runCatching {
            val a = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
                ?: return@runCatching null
            val street = listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ")
            listOf(street, a.locality ?: "").filter { it.isNotBlank() }.joinToString(", ")
                .ifBlank { a.getAddressLine(0) }
        }.getOrNull()
    }
}
