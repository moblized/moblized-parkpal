package com.moblized.parkpal.record

import android.content.Context
import android.location.Location
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Recorded locations waiting to go to Dawarich, kept in a file so nothing is lost without signal or if the
 * app is closed. Each line is one GeoJSON point in the format Dawarich's /api/v1/points accepts.
 */
object PointBuffer {
    /** Oldest points are dropped past this, so a long time without Dawarich can't fill the phone. */
    private const val MAX_POINTS = 100_000

    private val lock = Any()
    private var file: File? = null

    private val _pending = MutableStateFlow(0)
    /** How many points are waiting to upload. */
    val pending: StateFlow<Int> = _pending.asStateFlow()

    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val f = File(context.applicationContext.filesDir, "dawarich_points.jsonl")
            file = f
            _pending.value = if (f.exists()) f.useLines { lines -> lines.count { it.isNotBlank() } } else 0
        }
    }

    fun add(loc: Location, batteryLevel: Float?, charging: Boolean?, deviceId: String) {
        val props = JSONObject()
            .put("timestamp", loc.time / 1000)
            .put("device_id", deviceId)
        if (loc.hasAccuracy()) props.put("horizontal_accuracy", loc.accuracy.toDouble())
        if (loc.hasAltitude()) props.put("altitude", loc.altitude)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasVerticalAccuracy()) {
            props.put("vertical_accuracy", loc.verticalAccuracyMeters.toDouble())
        }
        if (loc.hasSpeed()) props.put("speed", loc.speed.toDouble())
        if (loc.hasBearing()) props.put("course", loc.bearing.toDouble())
        batteryLevel?.let { props.put("battery_level", it.toDouble()) }
        charging?.let { props.put("battery_state", if (it) "charging" else "unplugged") }
        val feature = JSONObject()
            .put("type", "Feature")
            .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(loc.longitude).put(loc.latitude)))
            .put("properties", props)
        synchronized(lock) {
            val f = file ?: return
            f.appendText(feature.toString() + "\n")
            _pending.value += 1
            if (_pending.value > MAX_POINTS) trimLocked(_pending.value - MAX_POINTS)
        }
    }

    /** Up to [max] of the oldest waiting points, as JSON strings. */
    fun peek(max: Int): List<String> = synchronized(lock) {
        val f = file ?: return emptyList()
        if (!f.exists()) return emptyList()
        f.useLines { lines -> lines.filter { it.isNotBlank() }.take(max).toList() }
    }

    /** Removes the oldest [count] points after they were uploaded. */
    fun drop(count: Int) = synchronized(lock) { trimLocked(count) }

    private fun trimLocked(count: Int) {
        val f = file ?: return
        if (!f.exists() || count <= 0) return
        val rest = f.readLines().filter { it.isNotBlank() }.drop(count)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(if (rest.isEmpty()) "" else rest.joinToString("\n", postfix = "\n"))
        tmp.renameTo(f)
        _pending.value = rest.size
    }
}
