package com.moblized.parkpal.nav

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Where you are on the route right now. */
data class Progress(
    /** Your position snapped onto the route line. */
    val lat: Double,
    val lon: Double,
    /** Direction of travel along the route, degrees from north. */
    val bearing: Float,
    /** Meters from the start of the route. */
    val along: Double,
    /** How far your GPS position is from the route line. */
    val offRoute: Double,
    /** Index of the route point just behind you (for drawing what's already driven). */
    val pointIndex: Int,
    /** Index into [NavRoute.steps] of the next instruction. */
    val nextStep: Int,
    val toNextStep: Double,
    val remainingMeters: Double,
    val remainingSeconds: Double,
)

/** Follows your position along a route: snapping, next instruction, time left, and when you've left the route. */
class NavEngine(val route: NavRoute) {
    private var seg = 0
    private var offCount = 0
    private var wrongWayCount = 0

    /** Your progress, and true if you've clearly left the route and need a new one. */
    fun update(lat: Double, lon: Double, accuracy: Float, speed: Float, heading: Float?): Pair<Progress, Boolean> {
        val n = route.size
        var best = match(lat, lon, max(0, seg - 3), min(n - 2, seg + 80))
        if (best.dist > 120) {
            // Lost the thread (GPS jump, tunnel): look along the whole route.
            val all = match(lat, lon, 0, n - 2)
            if (all.dist < best.dist) best = all
        }
        seg = best.seg
        val along = route.cum[best.seg] + best.t * (route.cum[best.seg + 1] - route.cum[best.seg])
        val segBearing = bearing(route.lats[best.seg], route.lons[best.seg], route.lats[best.seg + 1], route.lons[best.seg + 1])

        val walk = route.mode == TravelMode.WALK
        val limit = if (walk) 30.0 + min(accuracy.toDouble(), 30.0) else 45.0 + min(accuracy.toDouble(), 45.0)
        offCount = if (best.dist > limit) offCount + 1 else 0
        // Driving the opposite way along the route (e.g. after a U-turn) also needs a new route.
        val wrongWay = !walk && heading != null && speed > 5f && angleDiff(heading, segBearing) > 135f
        wrongWayCount = if (wrongWay) wrongWayCount + 1 else 0
        val lost = offCount >= (if (walk) 4 else 3) || wrongWayCount >= 4

        val steps = route.steps
        var next = steps.indexOfFirst { it.startDist > along + 1.0 }
        if (next < 0) next = steps.size - 1
        val toNext = max(0.0, steps[next].startDist - along)
        val remaining = max(0.0, route.cum.last() - along)
        // Time left: the rest of the current stretch, plus every stretch after it.
        val current = (next - 1).coerceAtLeast(0)
        val curLen = max(1.0, steps[next].startDist - steps[current].startDist)
        var seconds = steps[current].duration * (toNext / curLen).coerceIn(0.0, 1.0)
        for (i in next until steps.size) seconds += steps[i].duration
        if (seconds <= 0.0 && route.meters > 0) seconds = route.seconds * remaining / route.meters

        return Progress(
            lat = best.lat,
            lon = best.lon,
            bearing = segBearing,
            along = along,
            offRoute = best.dist,
            pointIndex = best.seg,
            nextStep = next,
            toNextStep = toNext,
            remainingMeters = remaining,
            remainingSeconds = seconds,
        ) to lost
    }

    private class Match(val seg: Int, val t: Double, val dist: Double, val lat: Double, val lon: Double)

    private fun match(lat: Double, lon: Double, from: Int, to: Int): Match {
        val kx = 111320.0 * cos(Math.toRadians(lat))
        val ky = 110540.0
        var best = Match(from, 0.0, Double.MAX_VALUE, route.lats[from], route.lons[from])
        for (i in from..to) {
            val ax = (route.lons[i] - lon) * kx
            val ay = (route.lats[i] - lat) * ky
            val bx = (route.lons[i + 1] - lon) * kx
            val by = (route.lats[i + 1] - lat) * ky
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val d = sqrt(px * px + py * py)
            // Prefer the later segment on a tie, so you don't snap backwards where a route doubles back.
            if (d < best.dist - 0.5 || (d <= best.dist + 0.5 && i > best.seg && i <= seg + 3)) {
                best = Match(
                    i,
                    t,
                    d,
                    route.lats[i] + t * (route.lats[i + 1] - route.lats[i]),
                    route.lons[i] + t * (route.lons[i + 1] - route.lons[i]),
                )
            }
        }
        return best
    }

    companion object {
        fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dl = Math.toRadians(lon2 - lon1)
            val y = kotlin.math.sin(dl) * cos(p2)
            val x = cos(p1) * kotlin.math.sin(p2) - kotlin.math.sin(p1) * cos(p2) * cos(dl)
            return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
        }

        fun angleDiff(a: Float, b: Float): Float {
            val d = abs(a - b) % 360f
            return if (d > 180f) 360f - d else d
        }
    }
}
