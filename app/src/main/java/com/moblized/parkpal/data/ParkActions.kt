package com.moblized.parkpal.data

import android.content.Context
import com.moblized.parkpal.timer.MeterAlarms
import com.moblized.parkpal.util.LocationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.max

/** Shared actions used by the phone app, widget and Android Auto. */
object ParkActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Saves your current GPS position as the parking spot. Returns null if location isn't available. */
    suspend fun parkHere(context: Context, destination: Destination? = ParkStore.currentTrip()): ParkingSession? {
        val loc = LocationHelper.current(context) ?: return null
        return parkAt(context, loc.latitude, loc.longitude, placeName = null, destination = destination)
    }

    /** Saves a spot at the given point (for example a garage picked on the map). */
    fun parkAt(
        context: Context,
        lat: Double,
        lon: Double,
        placeName: String?,
        destination: Destination? = ParkStore.currentTrip(),
    ): ParkingSession {
        val app = context.applicationContext
        if (ParkStore.active.value != null) ParkStore.endActive()
        MeterAlarms.cancel(app)
        val now = System.currentTimeMillis()
        val session = ParkingSession(
            id = now,
            lat = lat,
            lon = lon,
            placeName = placeName,
            startedAt = now,
            destName = destination?.title,
            destLat = destination?.lat,
            destLon = destination?.lon,
        )
        ParkStore.setActive(session)
        ParkStore.setTrip(null)
        // Fill in the street address in the background.
        scope.launch {
            val address = Geo.reverse(app, lat, lon) ?: return@launch
            ParkStore.updateActive { if (it.id == session.id) it.copy(address = address) else it }
        }
        return session
    }

    fun setMeter(context: Context, endsAt: Long?) {
        val current = ParkStore.active.value ?: return
        val updated = current.copy(meterEndsAt = endsAt)
        ParkStore.setActive(updated)
        if (endsAt == null) {
            MeterAlarms.cancel(context)
        } else {
            MeterAlarms.schedule(context, updated, SettingsStore.settings.value.reminderLeadMin)
        }
    }

    fun startMeter(context: Context, minutes: Int) {
        setMeter(context, System.currentTimeMillis() + minutes * 60_000L)
    }

    fun extendMeter(context: Context, minutes: Int) {
        val current = ParkStore.active.value ?: return
        val now = System.currentTimeMillis()
        val base = max(current.meterEndsAt ?: now, now)
        setMeter(context, base + minutes * 60_000L)
    }

    fun endParking(context: Context) {
        MeterAlarms.cancel(context)
        ParkStore.endActive()
    }
}
