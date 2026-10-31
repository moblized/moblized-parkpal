package com.moblized.parkpal.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Talks to your own ParkPal parking server (see server/README.md), e.g. on TrueNAS. */
object ParkingServer {
    data class Status(
        val ready: Boolean,
        val places: Long,
        val updated: String?,
        val busy: Boolean,
        val message: String,
        val googleEnabled: Boolean = false,
        val googleUsed: Int = 0,
        val googleLimit: Int = 0,
    )

    /** "parkpal.example.org/" -> "https://parkpal.example.org" */
    fun normalize(url: String): String {
        var u = url.trim().trimEnd('/')
        if (u.isEmpty()) return u
        if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) u = "https://$u"
        return u
    }

    suspend fun nearby(baseUrl: String, token: String, lat: Double, lon: Double, radiusMeters: Int): List<ParkingPlace> {
        val url = String.format(
            Locale.US, "%s/api/parking?lat=%.6f&lon=%.6f&radius=%d", normalize(baseUrl), lat, lon, radiusMeters,
        )
        val body = withContext(Dispatchers.IO) { get(url, token) }
        return withContext(Dispatchers.Default) { OverpassApi.parseElements(body) }
    }

    /** Explore places (gas, food…) from the server's /api/places. Older servers answer 404. */
    suspend fun places(
        baseUrl: String,
        token: String,
        category: String,
        lat: Double,
        lon: Double,
        radiusMeters: Int,
    ): List<Poi> {
        val url = String.format(
            Locale.US, "%s/api/places?lat=%.6f&lon=%.6f&radius=%d&category=%s",
            normalize(baseUrl), lat, lon, radiusMeters, category,
        )
        val body = withContext(Dispatchers.IO) { get(url, token) }
        val requested = PoiCategory.fromKey(category) ?: PoiCategory.GAS
        return withContext(Dispatchers.Default) { ExploreRepo.parse(body, requested) }
    }

    suspend fun status(baseUrl: String, token: String): Status = withContext(Dispatchers.IO) {
        val o = JSONObject(get("${normalize(baseUrl)}/api/status", token))
        val google = o.optJSONObject("google")
        Status(
            ready = o.optBoolean("ready"),
            places = o.optLong("places"),
            updated = if (o.isNull("updated")) null else o.optString("updated"),
            busy = o.optBoolean("busy"),
            message = o.optString("message"),
            googleEnabled = google?.optBoolean("enabled") ?: false,
            googleUsed = google?.optInt("used_this_month") ?: 0,
            googleLimit = google?.optInt("monthly_limit") ?: 0,
        )
    }

    private fun get(url: String, token: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", OverpassApi.USER_AGENT)
            if (token.isNotBlank()) conn.setRequestProperty("X-ParkPal-Token", token.trim())
            return when (val code = conn.responseCode) {
                in 200..299 -> conn.inputStream.bufferedReader().use { it.readText() }
                401 -> throw IOException("The server says the token is wrong")
                503 -> throw IOException("The server is still preparing parking data")
                else -> throw IOException("Server error (HTTP $code)")
            }
        } finally {
            conn.disconnect()
        }
    }
}
