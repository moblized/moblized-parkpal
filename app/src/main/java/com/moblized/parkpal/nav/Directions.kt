package com.moblized.parkpal.nav

import android.content.Context
import com.moblized.parkpal.data.Destination
import com.moblized.parkpal.data.NavApp
import com.moblized.parkpal.data.ParkingPlace
import com.moblized.parkpal.data.SettingsStore
import com.moblized.parkpal.util.LocationHelper
import com.moblized.parkpal.util.Nav

/** Starts directions: in ParkPal (turn-by-turn) or in Google Maps, depending on Settings. */
object Directions {
    /** Returns true if ParkPal's own navigation started. */
    fun start(context: Context, request: NavRequest): Boolean {
        val s = SettingsStore.settings.value
        if (s.navApp == NavApp.PARKPAL && LocationHelper.hasPermission(context)) {
            NavigationService.start(context, request)
            return true
        }
        Nav.navigate(context, request.dest.lat, request.dest.lon, walking = request.mode == TravelMode.WALK)
        return false
    }

    fun driveTo(context: Context, dest: Destination, offerParking: Boolean = true) =
        start(context, NavRequest(dest, TravelMode.DRIVE, offerParking = offerParking))

    /** Drive to a garage or lot, then offer to save it as your spot and walk on to [after]. */
    fun driveToParking(context: Context, place: ParkingPlace, after: Destination?) =
        start(
            context,
            NavRequest(
                dest = Destination(place.name, place.address ?: place.kind.label, place.lat, place.lon),
                mode = TravelMode.DRIVE,
                parking = place,
                after = after,
                offerParking = true,
            ),
        )

    fun walkTo(context: Context, dest: Destination, toCar: Boolean = false) =
        start(context, NavRequest(dest, TravelMode.WALK, toCar = toCar))
}
