package com.moblized.parkpal.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.moblized.parkpal.util.Format
import com.moblized.parkpal.util.distanceMeters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Keeps Dawarich in step with ParkPal:
 *  - finished parking stops become visits on your Dawarich timeline;
 *  - starred garages become Dawarich places;
 *  - your Dawarich places are cached for the "Where to?" search.
 *
 * Sending runs in the background when there's a connection, so a spot saved in a garage with no signal
 * still reaches Dawarich later. Only parking that ends after Dawarich is connected is sent.
 */
object DawarichSync {
    private const val TAG = "DawarichSync"
    private const val PREFS = "parkpal_dawarich"
    private const val WORK = "dawarich-sync"
    private const val PLACES_MAX_AGE_MS = 10 * 60_000L
    private const val VISIT_PREFIX = "Parked · "

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context

    private val _places = MutableStateFlow<List<Destination>>(emptyList())
    /** Your Dawarich places as destinations, most-visited first. */
    val places: StateFlow<List<Destination>> = _places.asStateFlow()
    private var placesLoadedAt = 0L

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _places.value = readDestinations(prefs.getString("places", null))
    }

    /** Asks the background sync to run (soon, once there's a connection). */
    fun kick(context: Context) {
        val s = SettingsStore.settings.value
        if (!Dawarich.configured(s)) return
        val request = OneTimeWorkRequestBuilder<DawarichWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Matches for the search box: your Dawarich places whose name contains the text. */
    fun matching(query: String, limit: Int = 4): List<Destination> {
        val q = query.trim()
        if (q.length < 2 || !SettingsStore.settings.value.dawarichPlaces) return emptyList()
        return _places.value.filter { it.title.contains(q, ignoreCase = true) }.take(limit)
    }

    /** Your places first, then search results that aren't the same place. */
    fun withMine(mine: List<Destination>, found: List<Destination>): List<Destination> =
        mine + found.filterNot { f ->
            mine.any { m -> m.title.equals(f.title, ignoreCase = true) && distanceMeters(m.lat, m.lon, f.lat, f.lon) < 150 }
        }

    /** Refreshes the cached Dawarich places if they're more than a few minutes old. */
    suspend fun refreshPlaces(force: Boolean = false) {
        val s = SettingsStore.settings.value
        if (!Dawarich.configured(s) || !s.dawarichPlaces) {
            _places.value = emptyList()
            return
        }
        if (!force && System.currentTimeMillis() - placesLoadedAt < PLACES_MAX_AGE_MS) return
        try {
            val list = Dawarich.places(s.dawarichUrl, s.dawarichKey)
                // Skip what ParkPal itself created: starred garages, and the places Dawarich makes for parking visits.
                .filterNot { it.note.startsWith(Dawarich.PARKPAL_NOTE) || it.name.startsWith(VISIT_PREFIX) }
                .sortedByDescending { it.visits }
                .map { p ->
                    val bits = buildList {
                        addAll(p.tags)
                        if (p.visits > 0) add(if (p.visits == 1) "1 visit" else "${p.visits} visits")
                    }
                    Destination(
                        title = p.name,
                        subtitle = (listOf("Dawarich") + bits).joinToString(" · "),
                        lat = p.lat,
                        lon = p.lon,
                        source = "dawarich",
                    )
                }
            _places.value = list
            placesLoadedAt = System.currentTimeMillis()
            prefs.edit().putString("places", writeDestinations(list)).apply()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't load Dawarich places: ${e.message}")
        }
    }

    /**
     * Sends anything not yet in Dawarich. Returns false if it should be retried later
     * (no connection, server down); bad requests are skipped so they don't block the rest.
     */
    internal suspend fun drain(): Boolean {
        val s = SettingsStore.settings.value
        if (!Dawarich.configured(s)) return true
        val server = Dawarich.normalize(s.dawarichUrl)
        val sinceKey = "since|$server"
        if (!prefs.contains(sinceKey)) prefs.edit().putLong(sinceKey, System.currentTimeMillis()).apply()
        val since = prefs.getLong(sinceKey, System.currentTimeMillis())

        if (s.dawarichVisits) {
            val sent = readIds("visits|$server")
            val pending = ParkStore.history.value
                .filter { (it.endedAt ?: 0L) >= since && it.endedAt != null && it.id.toString() !in sent }
                .sortedBy { it.startedAt }
            for (session in pending) {
                val ended = max(session.endedAt ?: System.currentTimeMillis(), session.startedAt + 60_000L)
                val name = buildString {
                    append(VISIT_PREFIX).append(session.title())
                    session.destName?.let { append(" (for ").append(it).append(")") }
                }
                val ok = sendOrSkip {
                    Dawarich.createVisit(s.dawarichUrl, s.dawarichKey, name, session.lat, session.lon, session.startedAt, ended)
                }
                if (!ok) return false
                addId("visits|$server", session.id.toString())
            }
        }
        if (s.dawarichFavorites) {
            val sent = readIds("places|$server")
            for (place in ParkStore.favorites.value.filter { it.id !in sent }) {
                val note = buildString {
                    append(Dawarich.PARKPAL_NOTE).append(" · ").append(place.kind.label).append(" · ").append(place.fee.label)
                    place.maxHeightMeters?.let { append(" · Height limit ").append(Format.metersToFtIn(it)) }
                }
                val ok = sendOrSkip {
                    Dawarich.createPlace(s.dawarichUrl, s.dawarichKey, place.name, place.lat, place.lon, note)
                }
                if (!ok) return false
                addId("places|$server", place.id)
            }
        }
        refreshPlaces(force = true)
        return true
    }

    /** True if sent (or permanently refused, so skip it); false if it should be retried later. */
    private suspend fun sendOrSkip(send: suspend () -> Unit): Boolean = try {
        send()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Dawarich.HttpError) {
        when (e.code) {
            400, 404, 422 -> {
                Log.w(TAG, "Dawarich refused an item, skipping it: ${e.message}")
                true
            }
            else -> false // bad key (401/403), rate limit or server error: try again later
        }
    } catch (e: Exception) {
        false // no connection etc.
    }

    private fun readIds(key: String): Set<String> =
        prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()

    private fun addId(key: String, id: String) {
        prefs.edit().putStringSet(key, readIds(key) + id).apply()
    }

    private fun writeDestinations(list: List<Destination>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    private fun readDestinations(raw: String?): List<Destination> {
        if (raw == null) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> runCatching { Destination.fromJson(arr.getJSONObject(i)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }
}

/** Background job that runs [DawarichSync.drain] when there's a connection. */
class DawarichWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SettingsStore.init(applicationContext)
        ParkStore.init(applicationContext)
        DawarichSync.init(applicationContext)
        return if (DawarichSync.drain()) Result.success() else if (runAttemptCount < 8) Result.retry() else Result.failure()
    }
}
