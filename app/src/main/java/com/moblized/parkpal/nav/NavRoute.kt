package com.moblized.parkpal.nav

import com.moblized.parkpal.data.OverpassApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class TravelMode { DRIVE, WALK }

/** What to draw on the direction banner. [angle] is the turn in degrees: negative is left, 0 straight. */
enum class ManeuverKind(val angle: Int) {
    DEPART(0),
    STRAIGHT(0),
    SLIGHT_LEFT(-40),
    LEFT(-90),
    SHARP_LEFT(-135),
    SLIGHT_RIGHT(40),
    RIGHT(90),
    SHARP_RIGHT(135),
    UTURN(180),
    MERGE_LEFT(-25),
    MERGE_RIGHT(25),
    RAMP_LEFT(-35),
    RAMP_RIGHT(35),
    FORK_LEFT(-30),
    FORK_RIGHT(30),
    ROUNDABOUT(0),
    ARRIVE(0),
}

/** One instruction: "Turn left onto Main Street", happening [startDist] meters into the route. */
data class NavStep(
    val kind: ManeuverKind,
    val text: String,
    /** Short form for "then …" hints: "turn left". */
    val short: String,
    val road: String?,
    val lat: Double,
    val lon: Double,
    val startDist: Double,
    val duration: Double,
    val exit: Int? = null,
)

/** A route with its line (as arrays, for fast matching) and its instructions. */
class NavRoute(
    val mode: TravelMode,
    val lats: DoubleArray,
    val lons: DoubleArray,
    /** Meters from the start to each point of the line. */
    val cum: DoubleArray,
    val steps: List<NavStep>,
    val meters: Double,
    val seconds: Double,
) {
    val size: Int get() = lats.size
}

/** Turn-by-turn routes from the free OSRM servers (the same data Google-Maps-like apps on OpenStreetMap use). */
object NavRouting {
    private val driveServers = listOf(
        "https://router.project-osrm.org/route/v1/driving/",
        "https://routing.openstreetmap.de/routed-car/route/v1/driving/",
    )
    private val walkServers = listOf(
        "https://routing.openstreetmap.de/routed-foot/route/v1/driving/",
    )

    /**
     * A route from here to there. [bearing] (the way you're heading) helps the first instruction match the
     * direction you're already going, so a reroute doesn't tell you to turn around for no reason.
     */
    suspend fun route(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        mode: TravelMode,
        bearing: Float? = null,
    ): NavRoute = withContext(Dispatchers.IO) {
        val coords = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", fromLon, fromLat, toLon, toLat)
        var query = "?overview=full&geometries=geojson&steps=true"
        if (bearing != null && mode == TravelMode.DRIVE) query += "&bearings=${bearing.roundToInt().mod(360)},60;"
        var last: Exception? = null
        for (server in if (mode == TravelMode.DRIVE) driveServers else walkServers) {
            try {
                val body = get(server + coords + query)
                return@withContext withContext(Dispatchers.Default) { parse(body, mode) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("No route found")
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", OverpassApi.USER_AGENT)
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Directions server busy (HTTP $code)")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    internal fun parse(body: String, mode: TravelMode): NavRoute {
        val json = JSONObject(body)
        if (json.optString("code") != "Ok") throw IOException("No route found (${json.optString("code")})")
        val route = json.optJSONArray("routes")?.optJSONObject(0) ?: throw IOException("No route found")
        val leg = route.optJSONArray("legs")?.optJSONObject(0) ?: throw IOException("No route found")
        val stepsJson = leg.optJSONArray("steps") ?: throw IOException("No route found")

        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        val cum = ArrayList<Double>()
        val steps = ArrayList<NavStep>()
        for (i in 0 until stepsJson.length()) {
            val step = stepsJson.optJSONObject(i) ?: continue
            val startIndex = (lats.size - 1).coerceAtLeast(0)
            val coords = step.optJSONObject("geometry")?.optJSONArray("coordinates")
            if (coords != null) {
                for (j in 0 until coords.length()) {
                    val c = coords.optJSONArray(j) ?: continue
                    val lat = c.optDouble(1)
                    val lon = c.optDouble(0)
                    if (lats.isNotEmpty() && lats.last() == lat && lons.last() == lon) continue
                    cum += if (lats.isEmpty()) 0.0 else cum.last() + meters(lats.last(), lons.last(), lat, lon)
                    lats += lat
                    lons += lon
                }
            }
            val startDist = if (cum.isEmpty()) 0.0 else cum[startIndex.coerceAtMost(cum.size - 1)]
            val made = describe(step, startDist, mode)
            if (made == null) {
                // Not worth its own instruction (e.g. a road changing its name mid-way): fold its time into the last one.
                if (steps.isNotEmpty()) {
                    val prev = steps.removeAt(steps.size - 1)
                    steps += prev.copy(duration = prev.duration + step.optDouble("duration", 0.0))
                }
            } else {
                steps += made
            }
        }
        if (lats.size < 2) throw IOException("No route found")
        return NavRoute(
            mode = mode,
            lats = lats.toDoubleArray(),
            lons = lons.toDoubleArray(),
            cum = cum.toDoubleArray(),
            steps = steps,
            meters = route.optDouble("distance", cum.last()),
            seconds = route.optDouble("duration", 0.0),
        )
    }

    private fun str(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "").trim()

    private fun describe(step: JSONObject, startDist: Double, mode: TravelMode): NavStep? {
        val m = step.optJSONObject("maneuver") ?: return null
        val type = str(m, "type")
        val mod = str(m, "modifier")
        val loc = m.optJSONArray("location")
        val lat = loc?.optDouble(1) ?: 0.0
        val lon = loc?.optDouble(0) ?: 0.0
        val duration = step.optDouble("duration", 0.0)
        val name = str(step, "name")
        val ref = str(step, "ref").split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" / ")
        val road = when {
            name.isNotEmpty() && ref.isNotEmpty() && !name.contains(ref, ignoreCase = true) -> "$name ($ref)"
            name.isNotEmpty() -> name
            ref.isNotEmpty() -> ref
            else -> null
        }
        val onto = road?.let { " onto $it" } ?: ""
        val destinations = str(step, "destinations")
        val (destRefs, destPlaces) = destinations.split(':', limit = 2).let {
            if (it.size == 2) it[0].trim() to it[1].trim() else "" to it[0].trim()
        }
        val toward = destPlaces.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(", ")
        val towardText = if (toward.isNotEmpty()) " toward $toward" else ""
        val left = mod.contains("left")

        fun make(kind: ManeuverKind, text: String, short: String, exit: Int? = null) =
            NavStep(kind, text, short, road, lat, lon, startDist, duration, exit)

        fun turn(): NavStep = when (mod) {
            "uturn" -> make(ManeuverKind.UTURN, "Make a U-turn" + (road?.let { " onto $it" } ?: ""), "make a U-turn")
            "sharp left" -> make(ManeuverKind.SHARP_LEFT, "Turn sharp left$onto", "turn sharp left")
            "left" -> make(ManeuverKind.LEFT, "Turn left$onto", "turn left")
            "slight left" -> make(ManeuverKind.SLIGHT_LEFT, "Bear left$onto", "bear left")
            "sharp right" -> make(ManeuverKind.SHARP_RIGHT, "Turn sharp right$onto", "turn sharp right")
            "right" -> make(ManeuverKind.RIGHT, "Turn right$onto", "turn right")
            "slight right" -> make(ManeuverKind.SLIGHT_RIGHT, "Bear right$onto", "bear right")
            else -> make(ManeuverKind.STRAIGHT, if (road != null) "Continue onto $road" else "Continue straight", "continue straight")
        }

        return when (type) {
            "depart" -> {
                val dir = compass(m.optDouble("bearing_after", 0.0))
                make(
                    ManeuverKind.DEPART,
                    (if (mode == TravelMode.WALK) "Walk $dir" else "Head $dir") + (road?.let { " on $it" } ?: ""),
                    "head $dir",
                )
            }
            "arrive" -> make(
                ManeuverKind.ARRIVE,
                when (mod) {
                    "left", "slight left", "sharp left" -> "Your destination is on the left"
                    "right", "slight right", "sharp right" -> "Your destination is on the right"
                    else -> "You have arrived"
                },
                "arrive",
            )
            "roundabout", "rotary" -> {
                val exit = m.optInt("exit", 0).takeIf { it > 0 }
                val what = if (type == "rotary") str(step, "rotary_name").ifEmpty { "the traffic circle" } else "the roundabout"
                if (exit != null) {
                    make(ManeuverKind.ROUNDABOUT, "At $what, take the ${ordinal(exit)} exit$onto", "take the ${ordinal(exit)} exit", exit)
                } else {
                    make(ManeuverKind.ROUNDABOUT, "Enter $what$onto", "enter $what")
                }
            }
            "roundabout turn" -> turn()
            "exit roundabout", "exit rotary", "notification", "use lane" -> null
            "merge" -> if (left) {
                make(ManeuverKind.MERGE_LEFT, "Merge left$onto", "merge left")
            } else {
                make(ManeuverKind.MERGE_RIGHT, "Merge right$onto", "merge right")
            }
            "on ramp" -> {
                val kind = if (left) ManeuverKind.RAMP_LEFT else ManeuverKind.RAMP_RIGHT
                val target = when {
                    road != null -> " onto $road"
                    destRefs.isNotEmpty() -> " onto $destRefs"
                    else -> ""
                }
                make(kind, "Take the ramp" + (if (left) " on the left" else "") + target + towardText, "take the ramp")
            }
            "off ramp" -> {
                val kind = if (left) ManeuverKind.RAMP_LEFT else ManeuverKind.RAMP_RIGHT
                val exits = str(step, "exits").split(';').firstOrNull()?.trim().orEmpty()
                val base = if (exits.isNotEmpty()) "Take exit $exits" else "Take the exit" + (if (left) " on the left" else "")
                val forRef = if (destRefs.isNotEmpty()) " for $destRefs" else ""
                val tail = if (toward.isNotEmpty() || forRef.isNotEmpty()) forRef + towardText else onto
                make(kind, base + tail, if (exits.isNotEmpty()) "take exit $exits" else "take the exit")
            }
            "fork" -> if (left) {
                make(ManeuverKind.FORK_LEFT, "Keep left at the fork" + (if (toward.isNotEmpty()) towardText else onto), "keep left")
            } else {
                make(ManeuverKind.FORK_RIGHT, "Keep right at the fork" + (if (toward.isNotEmpty()) towardText else onto), "keep right")
            }
            "end of road" -> if (left) {
                make(ManeuverKind.LEFT, "At the end of the road, turn left$onto", "turn left")
            } else {
                make(ManeuverKind.RIGHT, "At the end of the road, turn right$onto", "turn right")
            }
            "new name" -> if (mod == "straight" || mod.isEmpty()) null else turn()
            "continue" -> when (mod) {
                "slight left" -> make(ManeuverKind.SLIGHT_LEFT, "Keep left$onto", "keep left")
                "slight right" -> make(ManeuverKind.SLIGHT_RIGHT, "Keep right$onto", "keep right")
                "straight", "" -> null
                else -> turn()
            }
            else -> turn()
        }
    }

    private fun compass(bearing: Double): String {
        val names = arrayOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
        return names[(((bearing % 360) + 360) % 360 / 45.0).roundToInt() % 8]
    }

    private fun ordinal(n: Int): String = when (n) {
        1 -> "first"
        2 -> "second"
        3 -> "third"
        4 -> "fourth"
        5 -> "fifth"
        6 -> "sixth"
        7 -> "seventh"
        8 -> "eighth"
        else -> "${n}th"
    }

    fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
