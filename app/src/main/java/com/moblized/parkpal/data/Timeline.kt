package com.moblized.parkpal.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One day of your Dawarich timeline: where you stopped, how you got around, and the routes. */
data class TimelineDay(
    val date: LocalDate,
    val summary: TimelineSummary?,
    val entries: List<TimelineEntry>,
    /** Routes to draw: Dawarich's tracks, or (before Dawarich has made tracks) the day's raw points. */
    val routes: List<RouteLine>,
    /** True when [routes] came straight from recorded points because Dawarich hasn't processed the day yet. */
    val fromRawPoints: Boolean,
)

/** A drawn route; [trackId] links it to a journey in the list (null for raw recorded points). */
data class RouteLine(val trackId: Long?, val points: List<Pair<Double, Double>>)

data class TimelineSummary(
    val distance: Double,
    val distanceUnit: String,
    val places: Int,
    val movingMinutes: Int,
    val stationaryMinutes: Int,
)

sealed class TimelineEntry {
    abstract val startedAt: Instant
    abstract val endedAt: Instant

    data class Visit(
        val name: String,
        val status: String,
        val lat: Double?,
        val lon: Double?,
        override val startedAt: Instant,
        override val endedAt: Instant,
        val tags: List<String>,
    ) : TimelineEntry()

    data class Journey(
        val trackId: Long,
        override val startedAt: Instant,
        override val endedAt: Instant,
        val durationSeconds: Long,
        val distance: Double,
        val distanceUnit: String,
        val mode: String?,
        val avgSpeed: Double,
        val speedUnit: String,
    ) : TimelineEntry()
}

/** Reads your timeline from Dawarich. */
object TimelineApi {
    private const val POINT_PAGE = 2000
    private const val MAX_POINT_PAGES = 10
    private const val MAX_DRAWN_POINTS = 3000

    suspend fun day(url: String, key: String, date: LocalDate): TimelineDay = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toOffsetDateTime().toString()
        val end = date.atTime(LocalTime.of(23, 59, 59)).atZone(zone).toOffsetDateTime().toString()
        val range = "start_at=${enc(start)}&end_at=${enc(end)}"
        val base = Dawarich.normalize(url)

        val timeline = JSONObject(get("$base/api/v1/timeline?$range&distance_unit=mi", key))
        val days = timeline.optJSONArray("days") ?: JSONArray()
        val dayJson = (0 until days.length()).map { days.getJSONObject(it) }
            .firstOrNull { it.optString("date") == date.toString() }
            ?: (if (days.length() > 0) days.getJSONObject(0) else null)

        val summary = dayJson?.optJSONObject("summary")?.let { s ->
            TimelineSummary(
                distance = s.optDouble("total_distance", 0.0),
                distanceUnit = s.optString("distance_unit", "mi"),
                places = s.optInt("places_visited"),
                movingMinutes = s.optInt("time_moving_minutes"),
                stationaryMinutes = s.optInt("time_stationary_minutes"),
            )
        }
        val entriesJson = dayJson?.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until entriesJson.length()).mapNotNull { i -> parseEntry(entriesJson.getJSONObject(i)) }
            .filterNot { it is TimelineEntry.Visit && it.status == "declined" }

        // Routes: Dawarich's tracks for the day.
        val tracks = JSONObject(get("$base/api/v1/tracks?$range&per_page=100", key))
        val features = tracks.optJSONArray("features") ?: JSONArray()
        var routes = (0 until features.length()).mapNotNull { i ->
            val feature = features.getJSONObject(i)
            val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val points = (0 until coords.length()).mapNotNull { j ->
                val c = coords.optJSONArray(j) ?: return@mapNotNull null
                c.optDouble(1) to c.optDouble(0)
            }
            if (points.size < 2) return@mapNotNull null
            RouteLine(feature.optJSONObject("properties")?.optLong("id"), thin(points))
        }
        var fromRaw = false
        // Not processed yet: draw the recorded points directly.
        if (routes.isEmpty()) {
            val points = ArrayList<Pair<Double, Double>>()
            for (page in 1..MAX_POINT_PAGES) {
                val arr = JSONArray(get("$base/api/v1/points?$range&per_page=$POINT_PAGE&page=$page&order=asc", key))
                for (i in 0 until arr.length()) parsePoint(arr.getJSONObject(i))?.let { points += it }
                if (arr.length() < POINT_PAGE) break
            }
            if (points.size > 1) {
                routes = listOf(RouteLine(null, thin(points)))
                fromRaw = true
            }
        }
        TimelineDay(date, summary, entries, routes, fromRaw)
    }

    private fun parseEntry(o: JSONObject): TimelineEntry? {
        val started = runCatching { Instant.parse(o.getString("started_at")) }.getOrNull() ?: return null
        val ended = runCatching { Instant.parse(o.getString("ended_at")) }.getOrNull() ?: started
        return when (o.optString("type")) {
            "visit" -> {
                val place = o.optJSONObject("place")
                val center = o.optJSONObject("center")
                fun coord(obj: JSONObject?, key: String): Double? =
                    obj?.takeIf { it.has(key) && !it.isNull(key) }?.optDouble(key)?.takeIf { !it.isNaN() }
                val tagArr = o.optJSONArray("tags") ?: JSONArray()
                TimelineEntry.Visit(
                    name = o.optString("name").ifBlank { place?.optString("name").orEmpty() }.ifBlank { "Stop" },
                    status = o.optString("status"),
                    lat = coord(place, "lat") ?: coord(center, "lat") ?: coord(center, "latitude"),
                    lon = coord(place, "lng") ?: coord(center, "lng") ?: coord(center, "longitude"),
                    startedAt = started,
                    endedAt = ended,
                    tags = (0 until tagArr.length()).mapNotNull { tagArr.optJSONObject(it)?.optString("name")?.ifBlank { null } },
                )
            }
            "journey" -> TimelineEntry.Journey(
                trackId = o.optLong("track_id"),
                startedAt = started,
                endedAt = ended,
                durationSeconds = o.optLong("duration"),
                distance = o.optDouble("distance", 0.0),
                distanceUnit = o.optString("distance_unit", "mi"),
                mode = if (o.isNull("dominant_mode")) null else o.optString("dominant_mode").ifBlank { null },
                avgSpeed = o.optDouble("avg_speed", 0.0),
                speedUnit = o.optString("speed_unit", "mph"),
            )
            else -> null
        }
    }

    private fun parsePoint(o: JSONObject): Pair<Double, Double>? {
        val lat = o.optString("latitude").toDoubleOrNull()
        val lon = o.optString("longitude").toDoubleOrNull()
        if (lat != null && lon != null) return lat to lon
        // Fall back to "POINT (lon lat)"
        val m = Regex("""POINT\s*\(\s*(-?[\d.]+)\s+(-?[\d.]+)\s*\)""").find(o.optString("lonlat")) ?: return null
        val x = m.groupValues[1].toDoubleOrNull() ?: return null
        val y = m.groupValues[2].toDoubleOrNull() ?: return null
        return y to x
    }

    /** Keeps long days drawable: at most a few thousand points, always keeping the first and last. */
    private fun thin(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        if (points.size <= MAX_DRAWN_POINTS) return points
        val step = points.size.toDouble() / MAX_DRAWN_POINTS
        return (0 until MAX_DRAWN_POINTS).map { points[(it * step).toInt()] } + points.last()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, key: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer ${key.trim()}")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", OverpassApi.USER_AGENT)
            val code = conn.responseCode
            if (code in 200..299) return conn.inputStream.bufferedReader().use { it.readText() }
            throw IOException(
                when (code) {
                    401, 403 -> "Dawarich didn't accept the API key"
                    else -> "Dawarich error (HTTP $code)"
                },
            )
        } finally {
            conn.disconnect()
        }
    }
}
