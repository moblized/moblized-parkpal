package com.moblized.parkpal.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks turn instructions and position tracking. The hand-made route always runs; real routes fetched by CI
 * (DC streets, a highway trip, a walk) run when present, and their instructions are printed for review.
 */
class NavRoutingTest {
    /** Depart north on 1st St, turn right onto C St, arrive on the left. Roughly 220 m + 170 m. */
    private val handMade = """
        {"code":"Ok","routes":[{"distance":390,"duration":60,"legs":[{"steps":[
          {"name":"1st Street","ref":"","distance":220,"duration":35,
           "maneuver":{"type":"depart","modifier":"","bearing_after":0,"location":[-77.0100,38.8900]},
           "geometry":{"type":"LineString","coordinates":[[-77.0100,38.8900],[-77.0100,38.8910],[-77.0100,38.8920]]}},
          {"name":"C Street","ref":"","distance":170,"duration":25,
           "maneuver":{"type":"turn","modifier":"right","bearing_after":90,"location":[-77.0100,38.8920]},
           "geometry":{"type":"LineString","coordinates":[[-77.0100,38.8920],[-77.0090,38.8920],[-77.0080,38.8920]]}},
          {"name":"C Street","ref":"","distance":0,"duration":0,
           "maneuver":{"type":"arrive","modifier":"left","bearing_after":0,"location":[-77.0080,38.8920]},
           "geometry":{"type":"LineString","coordinates":[[-77.0080,38.8920],[-77.0080,38.8920]]}}
        ]}]}]}
    """.trimIndent()

    @Test
    fun handMadeRouteInstructions() {
        val r = NavRouting.parse(handMade, TravelMode.DRIVE)
        assertEquals(listOf(ManeuverKind.DEPART, ManeuverKind.RIGHT, ManeuverKind.ARRIVE), r.steps.map { it.kind })
        assertEquals("Head north on 1st Street", r.steps[0].text)
        assertEquals("Turn right onto C Street", r.steps[1].text)
        assertEquals("Your destination is on the left", r.steps[2].text)
        assertEquals(5, r.size)
        assertTrue("turn happens ~222 m in (${r.steps[1].startDist})", r.steps[1].startDist in 210.0..235.0)
        assertTrue(r.cum.last() in 380.0..410.0)
    }

    @Test
    fun followsHandMadeRouteAndNoticesLeavingIt() {
        val r = NavRouting.parse(handMade, TravelMode.DRIVE)
        val e = NavEngine(r)
        // Driving along, 3 m east of the line (normal GPS wobble).
        val (start, lost0) = e.update(38.8901, -77.00997, 8f, 10f, 0f)
        assertFalse(lost0)
        assertEquals(1, start.nextStep)
        assertTrue("about 210 m to the turn (${start.toNextStep})", start.toNextStep in 195.0..225.0)
        val (later, lost1) = e.update(38.8915, -77.00997, 8f, 10f, 0f)
        assertFalse(lost1)
        assertTrue(later.along > start.along)
        assertTrue(later.remainingSeconds < start.remainingSeconds)
        // After the turn.
        val (onC, _) = e.update(38.89203, -77.0090, 8f, 10f, 90f)
        assertEquals(2, onC.nextStep)
        assertEquals(90f, onC.bearing, 2f)
        // Three fixes 300 m off the route: time for a new route.
        var lost = false
        repeat(3) { lost = e.update(38.8950, -77.0040, 8f, 10f, 45f).second }
        assertTrue(lost)
    }

    @Test
    fun realRoutesFromCi() {
        val dir = File("src/test/resources/routes")
        val files = dir.listFiles { f -> f.name.endsWith(".json") && f.length() > 100 }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) {
            println("NAV|No fetched routes to check (offline)")
            return
        }
        for (f in files) {
            val mode = if (f.name.contains("walk")) TravelMode.WALK else TravelMode.DRIVE
            val r = NavRouting.parse(f.readText(), mode)
            // One line per route, so CI can show it as a single note.
            val lines = r.steps.joinToString(" ‖ ") { "${NavText.short(it.startDist)}: [${it.kind}] ${it.text}" }
            println("NAV|${f.nameWithoutExtension}: ${r.steps.size} instructions, ${"%.1f".format(r.meters / 1609.344)} mi, ${NavText.duration(r.seconds)} ‖ $lines")
            assertEquals(ManeuverKind.DEPART, r.steps.first().kind)
            assertEquals(ManeuverKind.ARRIVE, r.steps.last().kind)
            assertTrue(r.steps.zipWithNext().all { (a, b) -> b.startDist >= a.startDist })
            assertTrue("line length ${r.cum.last()} vs route ${r.meters}", kotlin.math.abs(r.cum.last() - r.meters) < r.meters * 0.05 + 30)

            // Drive the whole route point by point: never "lost", always moving forward.
            val e = NavEngine(r)
            var lastAlong = -1.0
            var lastStep = 0
            var i = 0
            while (i < r.size) {
                val heading = if (i + 1 < r.size) NavEngine.bearing(r.lats[i], r.lons[i], r.lats[i + 1], r.lons[i + 1]) else null
                val (p, lost) = e.update(r.lats[i], r.lons[i], 5f, if (mode == TravelMode.WALK) 1.4f else 12f, heading)
                assertFalse("lost at point $i of ${f.name}", lost)
                assertTrue("went backwards at point $i (${p.along} < $lastAlong)", p.along >= lastAlong - 1.0)
                assertTrue(p.nextStep >= lastStep)
                lastAlong = p.along
                lastStep = p.nextStep
                i += 1
            }
            assertTrue(lastAlong > r.cum.last() - 5)
        }
    }
}
