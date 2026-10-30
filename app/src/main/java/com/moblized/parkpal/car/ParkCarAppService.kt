package com.moblized.parkpal.car

import android.content.Intent
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarLocation
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.lifecycleScope
import com.moblized.parkpal.data.Clearance
import com.moblized.parkpal.data.FeeInfo
import com.moblized.parkpal.data.FitStatus
import com.moblized.parkpal.data.OverpassApi
import com.moblized.parkpal.data.ParkActions
import com.moblized.parkpal.data.ParkStore
import com.moblized.parkpal.data.ParkingPlace
import com.moblized.parkpal.data.SettingsStore
import com.moblized.parkpal.util.Format
import com.moblized.parkpal.util.LocationHelper
import com.moblized.parkpal.util.distanceMeters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Android Auto: nearby parking that fits your vehicle, shown on the car screen. */
class ParkCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    @Deprecated("Replaced by onCreateSession(SessionInfo)")
    override fun onCreateSession(): Session = ParkCarSession()

    override fun onCreateSession(sessionInfo: SessionInfo): Session = ParkCarSession()
}

class ParkCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = NearbyParkingScreen(carContext)
}

class NearbyParkingScreen(carContext: CarContext) : Screen(carContext) {
    private var places: List<ParkingPlace>? = null
    private var error: String? = null
    private var originLat = 0.0
    private var originLon = 0.0
    private var requested = false

    private fun refresh() {
        places = null
        error = null
        lifecycleScope.launch {
            val loc = LocationHelper.current(carContext)
            if (loc == null) {
                error = "Can't get your location. Open ParkPal on your phone once to allow location."
                invalidate()
                return@launch
            }
            originLat = loc.latitude
            originLon = loc.longitude
            try {
                val s = SettingsStore.settings.value
                places = OverpassApi.nearby(originLat, originLon, s.radiusMeters)
                    .filter { Clearance.fitStatus(it, s) != FitStatus.TOO_LOW }
                    .take(6)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Couldn't load parking. Check your phone's connection."
            }
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        if (!requested) {
            requested = true
            refresh()
        }
        val builder = PlaceListMapTemplate.Builder()
            .setTitle("Parking nearby")
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Refresh")
                            .setOnClickListener {
                                refresh()
                                invalidate()
                            }
                            .build(),
                    )
                    .build(),
            )
        if (LocationHelper.hasPermission(carContext)) builder.setCurrentLocationEnabled(true)

        val list = places
        val err = error
        if (list == null && err == null) return builder.setLoading(true).build()

        val items = ItemList.Builder()
        if (err != null) {
            items.setNoItemsMessage(err)
        } else if (list.isNullOrEmpty()) {
            items.setNoItemsMessage("No parking found nearby")
        } else {
            list.forEach { items.addItem(row(it)) }
        }
        return builder.setItemList(items.build()).build()
    }

    private fun row(p: ParkingPlace): Row {
        val s = SettingsStore.settings.value
        val status = Clearance.fitStatus(p, s)
        val meters = distanceMeters(originLat, originLon, p.lat, p.lon)
        val line = SpannableString("  · ${p.kind.label} · ${p.fee.label}")
        line.setSpan(
            DistanceSpan.create(Distance.create(meters / 1609.344, Distance.UNIT_MILES)),
            0, 1, Spannable.SPAN_INCLUSIVE_INCLUSIVE,
        )
        val color = when {
            status == FitStatus.CHECK_SIGN -> CarColor.YELLOW
            p.fee == FeeInfo.FREE -> CarColor.GREEN
            else -> CarColor.BLUE
        }
        val place = Place.Builder(CarLocation.create(p.lat, p.lon))
            .setMarker(PlaceMarker.Builder().setColor(color).build())
            .build()
        return Row.Builder()
            .setTitle(p.name)
            .addText(line)
            .addText(Clearance.describe(p, status, s))
            .setMetadata(Metadata.Builder().setPlace(place).build())
            .setOnClickListener { screenManager.push(ParkingDetailScreen(carContext, p, meters)) }
            .build()
    }
}

class ParkingDetailScreen(
    carContext: CarContext,
    private val place: ParkingPlace,
    private val meters: Double,
) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val s = SettingsStore.settings.value
        val status = Clearance.fitStatus(place, s)
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle("${place.kind.label} · ${place.fee.label} · ${Format.distance(meters)}")
                    .addText(Clearance.describe(place, status, s))
                    .build(),
            )
        place.openingHours?.let { pane.addRow(Row.Builder().setTitle("Hours").addText(it).build()) }
        pane.addAction(
            Action.Builder()
                .setTitle("Navigate")
                .setBackgroundColor(CarColor.BLUE)
                .setOnClickListener { navigate() }
                .build(),
        )
        pane.addAction(
            Action.Builder()
                .setTitle("Park here")
                .setOnClickListener { parkHere() }
                .build(),
        )
        val favorite = ParkStore.isFavorite(place.id)
        return PaneTemplate.Builder(pane.build())
            .setTitle(place.name)
            .setHeaderAction(Action.BACK)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(if (favorite) "Unsave" else "Save")
                            .setOnClickListener {
                                ParkStore.toggleFavorite(place)
                                invalidate()
                            }
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private fun navigate() {
        try {
            carContext.startCarApp(
                Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:${Format.latLon(place.lat, place.lon)}")),
            )
        } catch (e: Exception) {
            CarToast.makeText(carContext, "No navigation app found", CarToast.LENGTH_LONG).show()
        }
    }

    private fun parkHere() {
        ParkActions.parkAt(carContext, place.lat, place.lon, place.name)
        CarToast.makeText(carContext, "Saved as your parking spot", CarToast.LENGTH_LONG).show()
    }
}
