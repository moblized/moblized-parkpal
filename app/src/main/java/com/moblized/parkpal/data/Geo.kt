package com.moblized.parkpal.data

import android.content.Context
import android.location.Geocoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Place search. Type-ahead suggestions use Photon (built for search-as-you-type on OpenStreetMap data);
 * a full search falls back to Nominatim. Street addresses for saved spots use the phone's geocoder.
 */
object Geo {
    /** Recent suggestion results, so backspacing or retyping shows them instantly. */
    private val suggestCache = object : LinkedHashMap<String, List<Destination>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Destination>>?): Boolean = size > 60
    }

    private fun suggestKey(query: String, nearLat: Double?, nearLon: Double?, limit: Int): String =
        String.format(Locale.US, "%s|%.2f|%.2f|%d", query.trim().lowercase(Locale.US), nearLat ?: 0.0, nearLon ?: 0.0, limit)

    /** Suggestions already fetched for this text, or null. */
    fun cachedSuggestions(query: String, nearLat: Double?, nearLon: Double?, limit: Int = 7): List<Destination>? =
        synchronized(suggestCache) { suggestCache[suggestKey(query, nearLat, nearLon, limit)] }

    /** Suggestions while typing, biased toward the given point. */
    suspend fun suggest(query: String, nearLat: Double?, nearLon: Double?, limit: Int = 7): List<Destination> {
        cachedSuggestions(query, nearLat, nearLon, limit)?.let { return it }
        val result = withContext(Dispatchers.IO) {
            val url = StringBuilder("https://photon.komoot.io/api/?lang=en&limit=$limit&q=")
                .append(URLEncoder.encode(query.trim(), "UTF-8"))
            if (nearLat != null && nearLon != null) {
                url.append(String.format(Locale.US, "&lat=%.5f&lon=%.5f", nearLat, nearLon))
            }
            parsePhoton(get(url.toString()))
        }
        synchronized(suggestCache) { suggestCache[suggestKey(query, nearLat, nearLon, limit)] = result }
        return result
    }

    /** A full search when you press Search on the keyboard. */
    suspend fun search(query: String, nearLat: Double?, nearLon: Double?): List<Destination> {
        val photon = try {
            suggest(query, nearLat, nearLon, limit = 8)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        return photon.ifEmpty { nominatim(query, nearLat, nearLon) }
    }

    private suspend fun nominatim(query: String, nearLat: Double?, nearLon: Double?): List<Destination> =
        withContext(Dispatchers.IO) {
            val url = StringBuilder("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&q=")
                .append(URLEncoder.encode(query, "UTF-8"))
            if (nearLat != null && nearLon != null) {
                url.append(
                    String.format(
                        Locale.US, "&viewbox=%.4f,%.4f,%.4f,%.4f",
                        nearLon - 0.6, nearLat + 0.6, nearLon + 0.6, nearLat - 0.6,
                    ),
                )
            }
            val arr = JSONArray(get(url.toString()))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val display = o.optString("display_name")
                val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                val title = o.optString("name").ifBlank { display.substringBefore(",") }
                Destination(title = title, subtitle = display.substringAfter(", ", ""), lat = lat, lon = lon)
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

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", OverpassApi.USER_AGENT)
            if (conn.responseCode !in 200..299) throw IOException("Search failed (HTTP ${conn.responseCode})")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parsePhoton(json: String): List<Destination> {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Destination>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val lon = coords.optDouble(0)
            val lat = coords.optDouble(1)
            if (lat.isNaN() || lon.isNaN()) continue
            val p = f.optJSONObject("properties") ?: JSONObject()
            fun s(key: String): String? = p.optString(key, "").trim().ifEmpty { null }
            val streetLine = listOfNotNull(s("housenumber"), s("street")).joinToString(" ").ifBlank { null }
            val name = s("name")
            val locality = s("city") ?: s("town") ?: s("village") ?: s("district") ?: s("county")
            val title = name ?: streetLine ?: locality ?: continue
            val subtitle = listOfNotNull(
                if (name != null) streetLine else null,
                locality?.takeIf { it != title },
                s("state"),
            ).joinToString(", ")
            out += Destination(title = title, subtitle = subtitle, lat = lat, lon = lon)
        }
        return out.distinctBy { "${it.title}|${it.subtitle}" }
    }
}
