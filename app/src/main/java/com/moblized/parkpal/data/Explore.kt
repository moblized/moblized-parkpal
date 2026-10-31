package com.moblized.parkpal.data

import com.moblized.parkpal.util.Format
import com.moblized.parkpal.util.distanceMeters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

/** The Explore buttons on the map. [key] matches the ParkPal server's /api/places categories. */
enum class PoiCategory(
    val key: String,
    val label: String,
    val emoji: String,
    val color: Long,
    /** How far to look, in meters: rest areas are far apart, coffee isn't. */
    val radius: Int,
    /** Overpass selector, used when your own server isn't set up. */
    val overpass: String,
    val fallbackName: String,
) {
    GAS("gas", "Gas", "⛽", 0xFF2563EB, 6000, "nwr[\"amenity\"=\"fuel\"]", "Gas station"),
    FOOD("food", "Food", "🍔", 0xFFEA580C, 2000, "nwr[\"amenity\"~\"^(restaurant|fast_food)$\"]", "Restaurant"),
    COFFEE("coffee", "Coffee", "☕", 0xFF92400E, 2500, "nwr[\"amenity\"=\"cafe\"]", "Café"),
    GROCERIES("groceries", "Groceries", "🛒", 0xFF16A34A, 4000, "nwr[\"shop\"~\"^(supermarket|convenience)$\"]", "Grocery store"),
    HOTELS("hotels", "Hotels", "🛏", 0xFF7C3AED, 8000, "nwr[\"tourism\"~\"^(hotel|motel)$\"]", "Hotel"),
    REST("rest", "Rest areas", "🚻", 0xFF0F766E, 40000, "nwr[\"highway\"~\"^(rest_area|services)$\"]", "Rest area"),
    ;

    companion object {
        fun fromKey(key: String?): PoiCategory? = entries.firstOrNull { it.key == key }
    }
}

/** A gas station, restaurant, hotel… found by Explore. */
data class Poi(
    val id: String,
    val category: PoiCategory,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** Short description: cuisine, "Convenience store", brand… */
    val detail: String?,
    val address: String?,
    val openingHours: String?,
    val phone: String?,
    val website: String?,
    val diesel: Boolean,
    val trucks: Boolean,
    val source: String?,
) {
    fun toDestination(): Destination =
        Destination(title = name, subtitle = address ?: detail ?: category.fallbackName, lat = lat, lon = lon, source = "explore")
}

/**
 * Finds places for Explore: your own ParkPal server first (instant), else the public Overpass servers.
 * Results are kept for a while so flipping between categories is instant.
 */
object ExploreRepo {
    private const val GRID = 0.005
    private const val KEEP_MS = 60 * 60_000L
    private const val OVERPASS_LIMIT = 400

    private class Entry(val at: Long, val pois: List<Poi>)

    private val memory = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > 30
    }

    /** Places in [category] around a point, closest first. */
    suspend fun nearby(category: PoiCategory, lat: Double, lon: Double): List<Poi> {
        val cLat = (lat / GRID).roundToLong() * GRID
        val cLon = (lon / GRID).roundToLong() * GRID
        val radius = category.radius + 400
        val key = String.format(Locale.US, "%s_%.3f_%.3f", category.key, cLat, cLon)
        val now = System.currentTimeMillis()
        synchronized(memory) { memory[key] }?.takeIf { now - it.at < KEEP_MS }?.let { return sortFrom(it.pois, lat, lon) }
        val fresh = fetch(category, cLat, cLon, radius)
        if (fresh.isNotEmpty()) synchronized(memory) { memory[key] = Entry(now, fresh) }
        return sortFrom(fresh, lat, lon)
    }

    private suspend fun fetch(category: PoiCategory, lat: Double, lon: Double, radius: Int): List<Poi> {
        val s = SettingsStore.settings.value
        if (s.serverUrl.isNotBlank()) {
            try {
                val found = ParkingServer.places(s.serverUrl, s.serverToken, category.key, lat, lon, radius)
                // Empty can mean you're outside the regions your server keeps, so ask the public servers too.
                if (found.isNotEmpty()) return found
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Older server without Explore, still preparing, or unreachable: use the public servers.
            }
        }
        val query = "[out:json][timeout:20];" +
            "${category.overpass}(around:$radius,${Format.latLon(lat, lon)});" +
            "out center qt $OVERPASS_LIMIT;"
        val body = OverpassApi.fetchHedged(query)
        return withContext(Dispatchers.Default) { parse(body, category) }
    }

    private fun sortFrom(list: List<Poi>, lat: Double, lon: Double): List<Poi> =
        list.sortedBy { distanceMeters(lat, lon, it.lat, it.lon) }

    /** Reads Overpass-style JSON (also what your ParkPal server sends). */
    internal fun parse(json: String, requested: PoiCategory): List<Poi> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Poi>(elements.length())
        for (i in 0 until elements.length()) {
            val e = elements.optJSONObject(i) ?: continue
            val lat: Double
            val lon: Double
            if (e.has("lat")) {
                lat = e.optDouble("lat")
                lon = e.optDouble("lon")
            } else {
                val c = e.optJSONObject("center") ?: continue
                lat = c.optDouble("lat")
                lon = c.optDouble("lon")
            }
            if (lat.isNaN() || lon.isNaN()) continue
            val tags = e.optJSONObject("tags") ?: JSONObject()
            fun tag(k: String): String? = tags.optString(k, "").trim().ifEmpty { null }
            if (tag("access") == "private" || tag("access") == "no") continue
            val category = PoiCategory.fromKey(tag("parkpal:category")) ?: requested
            val brand = tag("brand")
            val name = tag("name") ?: brand ?: tag("operator") ?: category.fallbackName
            out += Poi(
                id = "${e.optString("type")}/${e.optString("id")}",
                category = category,
                name = name,
                lat = lat,
                lon = lon,
                detail = detail(category, ::tag, name, brand),
                address = tag("addr:full") ?: address(::tag),
                openingHours = tag("opening_hours"),
                phone = tag("phone") ?: tag("contact:phone"),
                website = tag("website") ?: tag("contact:website"),
                diesel = tag("fuel:diesel") == "yes",
                trucks = tag("hgv") in setOf("yes", "designated"),
                source = tag("parkpal:source"),
            )
        }
        return out
    }

    private fun detail(category: PoiCategory, tag: (String) -> String?, name: String, brand: String?): String? {
        val bits = mutableListOf<String>()
        when (category) {
            PoiCategory.FOOD -> {
                if (tag("amenity") == "fast_food") bits += "Fast food"
                tag("cuisine")?.let { bits += pretty(it) }
                if (tag("drive_through") == "yes") bits += "Drive-through"
            }
            PoiCategory.COFFEE -> {
                tag("cuisine")?.takeIf { it != "coffee_shop" }?.let { bits += pretty(it) }
                if (tag("drive_through") == "yes") bits += "Drive-through"
            }
            PoiCategory.GROCERIES -> bits += if (tag("shop") == "convenience") "Convenience store" else "Supermarket"
            PoiCategory.HOTELS -> {
                bits += if (tag("tourism") == "motel") "Motel" else "Hotel"
                tag("stars")?.toIntOrNull()?.let { bits += "★".repeat(it.coerceIn(1, 5)) }
            }
            PoiCategory.REST -> {
                bits += if (tag("highway") == "services") "Service plaza" else "Rest area"
                if (tag("toilets") == "yes") bits += "Restrooms"
            }
            PoiCategory.GAS -> if (brand != null && !name.contains(brand, ignoreCase = true)) bits += brand
        }
        return bits.joinToString(" · ").ifEmpty { null }
    }

    private fun pretty(raw: String): String =
        raw.split(';', ',').map { it.trim().replace('_', ' ') }.filter { it.isNotEmpty() }.take(3)
            .joinToString(", ") { w -> w.replaceFirstChar { it.titlecase(Locale.getDefault()) } }

    private fun address(tag: (String) -> String?): String? {
        val street = listOfNotNull(tag("addr:housenumber"), tag("addr:street")).joinToString(" ").ifEmpty { null }
        return listOfNotNull(street, tag("addr:city")).joinToString(", ").ifEmpty { null }
    }
}
