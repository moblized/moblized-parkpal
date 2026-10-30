package com.moblized.parkpal.data

import android.content.Context
import com.moblized.parkpal.util.distanceMeters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Parking lookups with a cache on the phone. Areas you've looked at before show instantly,
 * and only get re-downloaded when the saved copy is a few days old.
 */
object ParkingRepo {
    /** Searches are snapped to a ~200 m grid so nearby searches share the cache. */
    private const val GRID = 0.002
    private const val SNAP_PADDING_M = 150
    private const val FRESH_MS = 3L * 24 * 60 * 60 * 1000
    private const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    private class Entry(val savedAt: Long, val places: List<ParkingPlace>)

    private var dir: File? = null
    private val memory = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > 24
    }

    fun init(context: Context) {
        val d = File(context.cacheDir, "parking").apply { mkdirs() }
        dir = d
        // Tidy up very old areas in the background.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val cutoff = System.currentTimeMillis() - MAX_AGE_MS
            // Also clear out saves from build 4 (no "v2_" prefix), which could be missing parking.
            d.listFiles()?.filter { it.lastModified() < cutoff || !it.name.startsWith("v2_") }?.forEach { it.delete() }
        }
    }

    /**
     * Emits saved results right away (if any), then fresh results from the internet when the saved copy
     * is missing or old. Results are sorted closest-first to the point you asked about.
     */
    fun nearby(lat: Double, lon: Double, radiusMeters: Int): Flow<List<ParkingPlace>> = flow {
        val cLat = snap(lat)
        val cLon = snap(lon)
        val queryRadius = radiusMeters + SNAP_PADDING_M
        // "v2": ignore anything saved by build 4, which could be missing parking.
        val key = String.format(Locale.US, "v2_%.3f_%.3f_%d", cLat, cLon, queryRadius)
        val cached = read(key)
        if (cached != null) emit(sortFrom(cached.places, lat, lon))
        val now = System.currentTimeMillis()
        if (cached == null || now - cached.savedAt > FRESH_MS) {
            try {
                val fresh = OverpassApi.nearby(cLat, cLon, queryRadius)
                // Don't save an empty area: it's more likely a hiccup than truly no parking, so ask again next time.
                if (fresh.isNotEmpty()) write(key, Entry(now, fresh))
                if (cached == null || cached.places.map { it.id } != fresh.map { it.id }) {
                    emit(sortFrom(fresh, lat, lon))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Old saved results are better than nothing.
                if (cached == null) throw e
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun snap(v: Double): Double = (v / GRID).roundToLong() * GRID

    private fun sortFrom(places: List<ParkingPlace>, lat: Double, lon: Double): List<ParkingPlace> =
        places.sortedBy { distanceMeters(lat, lon, it.lat, it.lon) }

    private fun read(key: String): Entry? {
        synchronized(memory) { memory[key] }?.let { return it }
        val file = File(dir ?: return null, "$key.json")
        if (!file.exists()) return null
        return runCatching {
            val o = JSONObject(file.readText())
            val arr = o.getJSONArray("places")
            val entry = Entry(
                savedAt = o.getLong("savedAt"),
                places = (0 until arr.length()).map { ParkingPlace.fromJson(arr.getJSONObject(it)) },
            )
            synchronized(memory) { memory[key] = entry }
            entry
        }.getOrNull()
    }

    private fun write(key: String, entry: Entry) {
        synchronized(memory) { memory[key] = entry }
        val d = dir ?: return
        runCatching {
            val arr = JSONArray()
            entry.places.forEach { arr.put(it.toJson()) }
            File(d, "$key.json").writeText(JSONObject().put("savedAt", entry.savedAt).put("places", arr).toString())
        }
    }
}
