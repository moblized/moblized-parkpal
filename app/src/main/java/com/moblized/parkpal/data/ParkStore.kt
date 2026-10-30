package com.moblized.parkpal.data

import android.content.Context
import android.content.SharedPreferences
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
    private const val MAX_HISTORY = 100

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    private val _active = MutableStateFlow<ParkingSession?>(null)
    val active: StateFlow<ParkingSession?> = _active.asStateFlow()

    private val _favorites = MutableStateFlow<List<ParkingPlace>>(emptyList())
    val favorites: StateFlow<List<ParkingPlace>> = _favorites.asStateFlow()

    private val _history = MutableStateFlow<List<ParkingSession>>(emptyList())
    val history: StateFlow<List<ParkingSession>> = _history.asStateFlow()

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
