package com.moblized.parkpal.data

import com.moblized.parkpal.util.Format
import com.moblized.parkpal.util.distanceMeters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Finds parking from OpenStreetMap through the free Overpass API. */
object OverpassApi {
    const val USER_AGENT = "ParkPal/1.0 (personal Android app)"

    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )

    suspend fun nearby(lat: Double, lon: Double, radiusMeters: Int): List<ParkingPlace> =
        withContext(Dispatchers.IO) {
            val query = "[out:json][timeout:25];" +
                "nwr[\"amenity\"=\"parking\"](around:$radiusMeters,${Format.latLon(lat, lon)});" +
                "out center 300;"
            var lastError: Exception? = null
            for (endpoint in endpoints) {
                try {
                    val places = parse(post(endpoint, query))
                    return@withContext places.sortedBy { distanceMeters(lat, lon, it.lat, it.lon) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                }
            }
            throw lastError ?: IOException("No map server reachable")
        }

    private fun post(endpoint: String, query: String): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Map server busy (HTTP $code)")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String): List<ParkingPlace> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<ParkingPlace>(elements.length())
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val lat: Double
            val lon: Double
            if (e.has("lat")) {
                lat = e.getDouble("lat")
                lon = e.getDouble("lon")
            } else {
                val c = e.optJSONObject("center") ?: continue
                lat = c.getDouble("lat")
                lon = c.getDouble("lon")
            }
            val tags = e.optJSONObject("tags") ?: JSONObject()
            fun tag(key: String): String? = tags.optString(key, "").trim().ifEmpty { null }

            val access = tag("access")
            if (access == "private" || access == "no") continue

            val kind = when (tag("parking")) {
                "multi-storey", "rooftop", "carports" -> ParkingKind.GARAGE
                "underground" -> ParkingKind.UNDERGROUND
                "street_side", "lane", "on_kerb", "half_on_kerb", "shoulder" -> ParkingKind.STREET
                else -> if (tag("building") != null || tag("covered") == "yes") ParkingKind.GARAGE else ParkingKind.SURFACE
            }
            val fee = when {
                tag("fee") == "no" -> FeeInfo.FREE
                tag("fee") != null || tag("charge") != null -> FeeInfo.PAID
                else -> FeeInfo.UNKNOWN
            }
            val heightRaw = tag("maxheight") ?: tag("maxheight:physical")
            val operator = tag("operator")
            val name = tag("name")
                ?: operator?.let { "$it parking" }
                ?: when (kind) {
                    ParkingKind.GARAGE -> "Parking garage"
                    ParkingKind.UNDERGROUND -> "Underground parking"
                    ParkingKind.STREET -> "Street parking"
                    ParkingKind.SURFACE -> "Parking lot"
                }

            out += ParkingPlace(
                id = "${e.optString("type")}/${e.optLong("id")}",
                name = name,
                lat = lat,
                lon = lon,
                kind = kind,
                fee = fee,
                maxHeightMeters = Clearance.parseMaxHeight(heightRaw),
                maxHeightRaw = heightRaw,
                capacity = tag("capacity"),
                openingHours = tag("opening_hours"),
                operator = operator,
                access = access,
            )
        }
        return out
    }
}
