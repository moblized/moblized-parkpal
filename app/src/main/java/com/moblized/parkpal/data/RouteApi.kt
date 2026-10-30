package com.moblized.parkpal.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** Driving routes (line on the map plus drive time) from the free OSRM demo server. */
object RouteApi {
    data class Route(val points: List<Pair<Double, Double>>, val meters: Double, val seconds: Double)

    suspend fun drive(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Route? =
        withContext(Dispatchers.IO) {
            val url = String.format(
                Locale.US,
                "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
                fromLon, fromLat, toLon, toLat,
            )
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 12_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", OverpassApi.USER_AGENT)
                if (conn.responseCode !in 200..299) throw IOException("Route failed (HTTP ${conn.responseCode})")
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val route = json.optJSONArray("routes")?.optJSONObject(0) ?: return@withContext null
                val coords = route.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@withContext null
                val points = (0 until coords.length()).mapNotNull { i ->
                    val c = coords.optJSONArray(i) ?: return@mapNotNull null
                    c.optDouble(1) to c.optDouble(0)
                }
                Route(points, route.optDouble("distance"), route.optDouble("duration"))
            } finally {
                conn.disconnect()
            }
        }
}
