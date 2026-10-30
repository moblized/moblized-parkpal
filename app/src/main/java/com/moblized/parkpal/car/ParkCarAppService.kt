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
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.lifecycleScope
import com.moblized.parkpal.data.Clearance
import com.moblized.parkpal.data.Destination
import com.moblized.parkpal.data.FeeInfo
import com.moblized.parkpal.data.FitStatus
import com.moblized.parkpal.data.Geo
import com.moblized.parkpal.data.OverpassApi
import com.moblized.parkpal.data.ParkActions
import com.moblized.parkpal.data.ParkStore
import com.moblized.parkpal.data.ParkingPlace
import com.moblized.parkpal.data.SettingsStore
import com.moblized.parkpal.util.Format
import com.moblized.parkpal.util.LocationHelper
import com.moblized.parkpal.util.distanceMeters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Android Auto: parking that fits your vehicle, near you or near where you're going. */
class ParkCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    @Deprecated("Replaced by onCreateSession(SessionInfo)")
    override fun onCreateSession(): Session = ParkCarSession()

    override fun onCreateSession(sessionInfo: SessionInfo): Session = ParkCarSession()
}

class ParkCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = NearbyParkingScreen(carContext, destination = null)
}

/**
 * Parking list on a map. With no destination it shows parking around you; with a destination it shows
 * parking around that place, closest walk first.
 */
class NearbyParkingScreen(
    carContext: CarContext,
    private val destination: Destination?,
) : Screen(carContext) {
    private var places: List<Pair<ParkingPlace, Double>>? = null
    private var error: String? = null
    private var requested = false

    private fun refresh() {
        places = null
        error = null
        lifecycleScope.launch {
            val s = SettingsStore.settings.value
            val originLat: Double
            val originLon: Double
            if (destination != null) {
                originLat = destination.lat
                originLon = destination.lon
            } else {
                val loc = LocationHelper.current(carContext)
                if (loc == null) {
                    error = "Can't get your location. Open ParkPal on your phone once to allow location."
                    invalidate()
                    return@launch
                }
                originLat = loc.latitude
                originLon = loc.longitude
            }
            try {
                var radius = if (destination != null) s.walkRadiusMeters else s.radiusMeters
                var found = OverpassApi.nearby(originLat, originLon, radius)
                    .filter { Clearance.fitStatus(it, s) != FitStatus.TOO_LOW }
                if (destination != null && found.size < 3 && radius < 1600) {
                    radius = (radius * 2).coerceAtMost(1600)
                    found = OverpassApi.nearby(originLat, originLon, radius)
                        .filter { Clearance.fitStatus(it, s) != FitStatus.TOO_LOW }
                }
                places = found
                    .map { it to distanceMeters(originLat, originLon, it.lat, it.lon) }
                    .sortedBy { it.second }
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
        val refreshAction = Action.Builder()
            .setTitle("Refresh")
            .setOnClickListener {
                refresh()
                invalidate()
            }
            .build()
        val strip = ActionStrip.Builder()
        if (destination == null) {
            strip.addAction(
                Action.Builder()
                    .setTitle("Search")
                    .setOnClickListener { screenManager.push(DestinationSearchScreen(carContext)) }
                    .build(),
            )
        }
        strip.addAction(refreshAction)

        val builder = PlaceListMapTemplate.Builder()
            .setTitle(destination?.let { "Parking near ${it.title}" } ?: "Parking nearby")
            .setHeaderAction(if (destination != null) Action.BACK else Action.APP_ICON)
            .setActionStrip(strip.build())
        if (LocationHelper.hasPermission(carContext)) builder.setCurrentLocationEnabled(true)
        destination?.let { d ->
            builder.setAnchor(
                Place.Builder(CarLocation.create(d.lat, d.lon))
                    .setMarker(PlaceMarker.Builder().setColor(CarColor.RED).build())
                    .build(),
            )
        }

        val list = places
        val err = error
        if (list == null && err == null) return builder.setLoading(true).build()

        val items = ItemList.Builder()
        when {
            err != null -> items.setNoItemsMessage(err)
            list.isNullOrEmpty() -> items.setNoItemsMessage(
                if (destination != null) "No parking mapped near here. Check for street parking." else "No parking found nearby",
            )
            else -> list.forEach { (place, meters) -> items.addItem(row(place, meters)) }
        }
        return builder.setItemList(items.build()).build()
    }

    private fun row(p: ParkingPlace, meters: Double): Row {
        val s = SettingsStore.settings.value
        val status = Clearance.fitStatus(p, s)
        val details = listOfNotNull(
            if (destination != null) Format.walk(meters) else null,
            p.kind.label,
            p.fee.label,
        ).joinToString(" · ")
        // The first character is replaced by the distance.
        val line = SpannableString("  · $details")
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
            .setOnClickListener { screenManager.push(ParkingDetailScreen(carContext, p, destination)) }
            .build()
    }
}

/** Pick where you're going. Shows recent destinations until you type. */
class DestinationSearchScreen(carContext: CarContext) : Screen(carContext) {
    private var results: List<Destination>? = null
    private var searching = false
    private var job: Job? = null

    private fun search(text: String, delayMs: Long) {
        job?.cancel()
        val q = text.trim()
        if (q.length < 3) {
            results = null
            searching = false
            invalidate()
            return
        }
        job = lifecycleScope.launch {
            delay(delayMs)
            searching = true
            invalidate()
            val loc = LocationHelper.quick(carContext)
            results = try {
                Geo.suggest(q, loc?.latitude, loc?.longitude, limit = 6)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            searching = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val builder = SearchTemplate.Builder(
            object : SearchTemplate.SearchCallback {
                override fun onSearchTextChanged(searchText: String) = search(searchText, 600)

                override fun onSearchSubmitted(searchText: String) = search(searchText, 0)
            },
        )
            .setHeaderAction(Action.BACK)
            .setSearchHint("Where to?")
            .setShowKeyboardByDefault(false)
        if (searching) return builder.setLoading(true).build()

        val shown = results ?: ParkStore.recents.value
        val items = ItemList.Builder()
        if (shown.isEmpty()) {
            items.setNoItemsMessage(if (results == null) "Search for where you're going" else "No places found")
        }
        shown.take(6).forEach { d ->
            val row = Row.Builder().setTitle(d.title)
            if (d.subtitle.isNotBlank()) row.addText(d.subtitle)
            row.setOnClickListener {
                ParkStore.addRecent(d)
                screenManager.push(NearbyParkingScreen(carContext, d))
            }
            items.addItem(row.build())
        }
        return builder.setItemList(items.build()).build()
    }
}

class ParkingDetailScreen(
    carContext: CarContext,
    private val place: ParkingPlace,
    private val destination: Destination?,
) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val s = SettingsStore.settings.value
        val status = Clearance.fitStatus(place, s)
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle("${place.kind.label} · ${place.fee.label}")
                    .addText(Clearance.describe(place, status, s))
                    .build(),
            )
        destination?.let { d ->
            val walk = distanceMeters(place.lat, place.lon, d.lat, d.lon)
            pane.addRow(Row.Builder().setTitle("${Format.walk(walk)} to ${d.title}").build())
        }
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
        destination?.let { ParkStore.setTrip(it) }
        try {
            carContext.startCarApp(
                Intent(CarContext.ACTION_NAVIGATE, Uri.parse("geo:${Format.latLon(place.lat, place.lon)}")),
            )
        } catch (e: Exception) {
            CarToast.makeText(carContext, "No navigation app found", CarToast.LENGTH_LONG).show()
        }
    }

    private fun parkHere() {
        ParkActions.parkAt(carContext, place.lat, place.lon, place.name, destination ?: ParkStore.currentTrip())
        CarToast.makeText(carContext, "Saved as your parking spot", CarToast.LENGTH_LONG).show()
    }
}
