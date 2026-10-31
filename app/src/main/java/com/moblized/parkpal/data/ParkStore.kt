package com.moblized.parkpal.data

import android.content.Context
import android.content.SharedPreferences
import com.moblized.parkpal.util.distanceMeters
import com.moblized.parkpal.widget.WidgetRefresher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saves the current spot, favorites and parking history on the phone. */
object ParkStore {
    private const val KEY_ACTIVE = "active"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_HISTORY = "history"
    private const val KEY_RECENTS = "recents"
    private const val KEY_TRIP = "trip"
    private const val KEY_TRIP_AT = "tripAt"
    private const val MAX_HISTORY = 100
    private const val MAX_RECENTS = 8
    private const val TRIP_TTL_MS = 4 * 60 * 60 * 1000L

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    private val _active = MutableStateFlow<ParkingSession?>(null)
    val active: StateFlow<ParkingSession?> = _active.asStateFlow()

    private val _favorites = MutableStateFlow<List<ParkingPlace>>(emptyList())
    val favorites: StateFlow<List<ParkingPlace>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow<List<ParkingSession>>(emptyList())
    val history: StateFlow<List<ParkingSession>> = _history.asStateFlow()

    private val _recents = MutableStateFlow<List<Destination>>(emptyList())
    val recents: StateFlow<List<Destination>> = _recents.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences("parkpal_store", Context.MODE_PRIVATE)
        _active.value = prefs.getString(KEY_ACTIVE, null)?.let { raw ->
            runCatching { ParkingSession.fromJson(JSONObject(raw)) }.getOrNull()
        }
        _favorites.value = readList(KEY_FAVORITES) { ParkingPlace.fromJson(it) }
        _history.value = readList(KEY_HISTORY) { ParkingSession.fromJson(it) }
        _recents.value = readList(KEY_RECENTS) { Destination.fromJson(it) }
    }

    @Synchronized
    fun addRecent(d: Destination) {
        val updated = (listOf(d) + _recents.value.filterNot { sameDestination(it, d) }).take(MAX_RECENTS)
        _recents.value = updated
        writeList(KEY_RECENTS, updated.map { it.toJson() })
    }

    @Synchronized
    fun clearRecents() {
        _recents.value = emptyList()
        writeList(KEY_RECENTS, emptyList())
    }

    private fun sameDestination(a: Destination, b: Destination): Boolean =
        a.title == b.title && distanceMeters(a.lat, a.lon, b.lat, b.lon) < 60

    /** Remembers where you're headed, so the spot you save next can link to it. */
    @Synchronized
    fun setTrip(d: Destination?) {
        val editor = prefs.edit()
        if (d == null) {
            editor.remove(KEY_TRIP).remove(KEY_TRIP_AT)
        } else {
            editor.putString(KEY_TRIP, d.toJson().toString()).putLong(KEY_TRIP_AT, System.currentTimeMillis())
        }
        editor.apply()
    }

    /** The destination you set out for in the last few hours, if any. */
    fun currentTrip(): Destination? {
        if (!::prefs.isInitialized) return null
        val at = prefs.getLong(KEY_TRIP_AT, 0L)
        if (System.currentTimeMillis() - at > TRIP_TTL_MS) return null
        val raw = prefs.getString(KEY_TRIP, null) ?: return null
        return runCatching { Destination.fromJson(JSONObject(raw)) }.getOrNull()
    }

    @Synchronized
    fun setActive(session: ParkingSession?) {
        _active.value = session
        val editor = prefs.edit()
        if (session == null) editor.remove(KEY_ACTIVE) else editor.putString(KEY_ACTIVE, session.toJson().toString())
        editor.apply()
        WidgetRefresher.refresh(appContext)
    }

    @Synchronized
    fun updateActive(transform: (ParkingSession) -> ParkingSession) {
        val current = _active.value ?: return
        setActive(transform(current))
    }

    /** Moves the current spot into history. */
    @Synchronized
    fun endActive() {
        val current = _active.value ?: return
        val ended = current.copy(endedAt = System.currentTimeMillis())
        val all = listOf(ended) + _history.value
        all.drop(MAX_HISTORY).forEach { deletePhoto(it.photoPath) }
        setHistory(all.take(MAX_HISTORY))
        setActive(null)
        // Send the finished stop to Dawarich (if connected).
        DawarichSync.kick(appContext)
    }

    @Synchronized
    fun deleteHistory(id: Long) {
        _history.value.firstOrNull { it.id == id }?.let { deletePhoto(it.photoPath) }
        setHistory(_history.value.filterNot { it.id == id })
    }

    @Synchronized
    fun clearHistory() {
        _history.value.forEach { deletePhoto(it.photoPath) }
        setHistory(emptyList())
    }

    fun isFavorite(id: String): Boolean = _favorites.value.any { it.id == id }

    @Synchronized
    fun toggleFavorite(place: ParkingPlace) {
        val list = _favorites.value
        val updated = if (list.any { it.id == place.id }) list.filterNot { it.id == place.id } else listOf(place) + list
        _favorites.value = updated
        writeList(KEY_FAVORITES, updated.map { it.toJson() })
        // Newly starred garages go to Dawarich as places (if connected). Un-starring leaves Dawarich alone.
        if (updated.size > list.size) DawarichSync.kick(appContext)
    }

    fun deletePhoto(path: String?) {
        if (path != null) runCatching { File(path).delete() }
    }

    private fun setHistory(list: List<ParkingSession>) {
        _history.value = list
        writeList(KEY_HISTORY, list.map { it.toJson() })
    }

    private fun <T> readList(key: String, parse: (JSONObject) -> T): List<T> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> runCatching { parse(arr.getJSONObject(i)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    private fun writeList(key: String, items: List<JSONObject>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        prefs.edit().putString(key, arr.toString()).apply()
    }
}
