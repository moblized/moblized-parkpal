package com.moblized.parkpal.data

import com.moblized.parkpal.util.Format
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList

/** Finds parking from OpenStreetMap through the free Overpass API. */
object OverpassApi {
    const val USER_AGENT = "ParkPal/1.0 (personal Android app)"

    /** If a server hasn't answered after this long, ask the next one too and take whichever is first. */
    private const val HEDGE_DELAY_MS = 2_500L

    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Parking within [radiusMeters] of a point, in no particular order. */
    suspend fun nearby(lat: Double, lon: Double, radiusMeters: Int): List<ParkingPlace> {
        // "out center" keeps coordinates for parking mapped as single points and a center point for lots.
        val query = "[out:json][timeout:20];" +
            "nwr[\"amenity\"=\"parking\"](around:$radiusMeters,${Format.latLon(lat, lon)});" +
            "out center qt 500;"
        val body = fetchHedged(query)
        return withContext(Dispatchers.Default) { parseElements(body) }
    }

    /**
     * Sends the query to the first server; if it's slow or fails, also tries the next one,
     * and returns the first good answer. Slow requests left over are cut off.
     */
    internal suspend fun fetchHedged(query: String): String {
        val winner = CompletableDeferred<String>()
        val connections = CopyOnWriteArrayList<HttpURLConnection>()
        val lock = Any()
        var started = 0
        var failures = 0
        var closed = false

        fun startNext() {
            val endpoint = synchronized(lock) {
                if (closed || started >= endpoints.size) return
                endpoints[started++]
            }
            ioScope.launch {
                try {
                    winner.complete(post(endpoint, query, connections))
                } catch (e: Exception) {
                    var startAnother = false
                    synchronized(lock) {
                        failures++
                        if (failures >= endpoints.size) {
                            winner.completeExceptionally(e)
                        } else if (failures == started) {
                            // Nothing else is in flight, so don't wait for the hedge timer.
                            startAnother = true
                        }
                    }
                    if (startAnother) startNext()
                }
            }
        }

        try {
            startNext()
            while (true) {
                val body = withTimeoutOrNull(HEDGE_DELAY_MS) { winner.await() }
                if (body != null) return body
                val more = synchronized(lock) { started < endpoints.size }
                if (!more) return winner.await()
                startNext()
            }
        } finally {
            synchronized(lock) { closed = true }
            connections.forEach { runCatching { it.disconnect() } }
        }
    }

    private fun post(endpoint: String, query: String, connections: MutableList<HttpURLConnection>): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        connections += conn
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("Map server busy (HTTP $code)")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        // A timed-out query still answers "OK", just with an error note and missing results. Count it as a
        // failure so the next server is tried, instead of showing (and saving) an empty area.
        if (body.contains("\"remark\"")) {
            val remark = runCatching { JSONObject(body).optString("remark") }.getOrDefault("")
            if (remark.contains("error", ignoreCase = true)) throw IOException("Map server busy ($remark)")
        }
        return body
    }

    /** Reads Overpass-style JSON. Your own ParkPal server answers in the same format. */
    internal fun parseElements(json: String): List<ParkingPlace> {
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

            val source = tag("parkpal:source")
            val kind = when (tag("parking")) {
                "multi-storey", "rooftop", "carports" -> ParkingKind.GARAGE
                "underground" -> ParkingKind.UNDERGROUND
                "street_side", "lane", "on_kerb", "half_on_kerb", "shoulder" -> ParkingKind.STREET
                "surface" -> ParkingKind.SURFACE
                else -> when {
                    tag("building") != null || tag("covered") == "yes" -> ParkingKind.GARAGE
                    source == "google" -> ParkingKind.UNKNOWN
                    else -> ParkingKind.SURFACE
                }
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
                    ParkingKind.UNKNOWN -> "Parking"
                }

            out += ParkingPlace(
                // OpenStreetMap ids are numbers; Google place ids (from your server) are text.
                id = "${e.optString("type")}/${e.optString("id")}",
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
                source = source,
                address = tag("addr:full"),
            )
        }
        return out
    }
}
