package com.moblized.parkpal.ui

import android.app.Application
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moblized.parkpal.data.AppSettings
import com.moblized.parkpal.data.Clearance
import com.moblized.parkpal.data.Dawarich
import com.moblized.parkpal.data.DawarichSync
import com.moblized.parkpal.data.Destination
import com.moblized.parkpal.data.ExploreRepo
import com.moblized.parkpal.data.FeeInfo
import com.moblized.parkpal.data.FitStatus
import com.moblized.parkpal.data.Geo
import com.moblized.parkpal.data.ParkActions
import com.moblized.parkpal.data.ParkingRepo
import com.moblized.parkpal.data.ParkStore
import com.moblized.parkpal.data.ParkingKind
import com.moblized.parkpal.data.ParkingPlace
import com.moblized.parkpal.data.ParkingSession
import com.moblized.parkpal.data.Poi
import com.moblized.parkpal.data.PoiCategory
import com.moblized.parkpal.data.RouteApi
import com.moblized.parkpal.data.SettingsStore
import com.moblized.parkpal.nav.Directions
import com.moblized.parkpal.update.UpdateInfo
import com.moblized.parkpal.update.Updater
import com.moblized.parkpal.util.Format
import com.moblized.parkpal.util.LocationHelper
import com.moblized.parkpal.util.Nav
import com.moblized.parkpal.util.distanceMeters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class AppTab(val label: String, val icon: ImageVector) {
    MAP("Explore", Icons.Filled.Map),
    TIMELINE("Timeline", Icons.Filled.Timeline),
    SPOT("My spot", Icons.Filled.DirectionsCar),
    SAVED("Saved", Icons.Filled.Star),
    SETTINGS("Settings", Icons.Filled.Settings),
}

enum class TypeFilter { ALL, COVERED, OPEN }

data class Filters(
    val fitsOnly: Boolean = true,
    val freeOnly: Boolean = false,
    val type: TypeFilter = TypeFilter.ALL,
)

data class MapFocus(val lat: Double, val lon: Double, val zoom: Double)

/** A parking option for the current destination. */
data class TripItem(val place: ParkingPlace, val status: FitStatus, val walkMeters: Double)

enum class TripAdvice {
    /** Automatic search is off and you haven't asked yet. */
    NOT_SEARCHED,
    SEARCHING,
    /** You're already close enough to walk. */
    WALKABLE,
    /** The destination has its own parking. */
    ON_SITE,
    FOUND,
    NONE,
}

data class TripSummary(
    val advice: TripAdvice,
    val youToDestMeters: Double? = null,
    val onSite: TripItem? = null,
)

data class RouteUi(val placeId: String, val points: List<Pair<Double, Double>>, val meters: Double, val seconds: Double)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication()

    val settings: StateFlow<AppSettings> = SettingsStore.settings
    val active: StateFlow<ParkingSession?> = ParkStore.active
    val favorites: StateFlow<List<ParkingPlace>> = ParkStore.favorites
    val history: StateFlow<List<ParkingSession>> = ParkStore.history
    val recents: StateFlow<List<Destination>> = ParkStore.recents

    private val _places = MutableStateFlow<List<ParkingPlace>>(emptyList())
    private val _filters = MutableStateFlow(Filters())
    val filters: StateFlow<Filters> = _filters.asStateFlow()

    val visiblePlaces: StateFlow<List<Pair<ParkingPlace, FitStatus>>> =
        combine(_places, _filters, SettingsStore.settings) { list, f, s ->
            list.map { it to Clearance.fitStatus(it, s) }.filter { (p, status) ->
                val typeOk = when (f.type) {
                    TypeFilter.ALL -> true
                    TypeFilter.COVERED -> p.kind.covered
                    TypeFilter.OPEN -> !p.kind.covered
                }
                typeOk && (!f.fitsOnly || status != FitStatus.TOO_LOW) && (!f.freeOnly || p.fee == FeeInfo.FREE)
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val totalFound: StateFlow<Int> =
        _places.map { it.size }.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _selected = MutableStateFlow<ParkingPlace?>(null)
    val selected: StateFlow<ParkingPlace?> = _selected.asStateFlow()

    private val _tab = MutableStateFlow(AppTab.MAP)
    val tab: StateFlow<AppTab> = _tab.asStateFlow()

    private val _focus = MutableStateFlow<MapFocus?>(null)
    val focus: StateFlow<MapFocus?> = _focus.asStateFlow()

    /** Points the map should zoom to fit (destination plus the nearest parking). */
    private val _fit = MutableStateFlow<List<Pair<Double, Double>>?>(null)
    val fit: StateFlow<List<Pair<Double, Double>>?> = _fit.asStateFlow()

    // ---- Destination ("Where to?") ----

    private val _destination = MutableStateFlow<Destination?>(null)
    val destination: StateFlow<Destination?> = _destination.asStateFlow()

    private val _searchedDest = MutableStateFlow(false)
    private val _tripRadius = MutableStateFlow(0)
    val tripRadius: StateFlow<Int> = _tripRadius.asStateFlow()

    private val _sheetExpanded = MutableStateFlow(true)
    val sheetExpanded: StateFlow<Boolean> = _sheetExpanded.asStateFlow()

    private val _suggestions = MutableStateFlow<List<Destination>>(emptyList())
    val suggestions: StateFlow<List<Destination>> = _suggestions.asStateFlow()

    private val _userLoc = MutableStateFlow<Pair<Double, Double>?>(null)
    private var userLocAt = 0L

    private val _route = MutableStateFlow<RouteUi?>(null)
    val route: StateFlow<RouteUi?> = _route.asStateFlow()

    private val _routeLoading = MutableStateFlow(false)
    val routeLoading: StateFlow<Boolean> = _routeLoading.asStateFlow()

    val tripItems: StateFlow<List<TripItem>> =
        combine(visiblePlaces, _destination) { list, dest ->
            if (dest == null) {
                emptyList()
            } else {
                list.map { (p, status) -> TripItem(p, status, distanceMeters(dest.lat, dest.lon, p.lat, p.lon)) }
                    .sortedWith(compareBy<TripItem>({ it.status == FitStatus.TOO_LOW }, { it.walkMeters }))
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val tripSummary: StateFlow<TripSummary?> =
        combine(_destination, tripItems, _userLoc, _loading, _searchedDest) { dest, items, me, loading, searched ->
            if (dest == null) return@combine null
            val youToDest = me?.let { distanceMeters(it.first, it.second, dest.lat, dest.lon) }
            val onSite = items.firstOrNull {
                it.walkMeters <= ON_SITE_METERS && it.status != FitStatus.TOO_LOW && it.place.kind != ParkingKind.STREET
            }
            val advice = when {
                !searched -> TripAdvice.NOT_SEARCHED
                loading && items.isEmpty() -> TripAdvice.SEARCHING
                youToDest != null && youToDest <= WALKABLE_METERS -> TripAdvice.WALKABLE
                onSite != null -> TripAdvice.ON_SITE
                items.isEmpty() -> TripAdvice.NONE
                else -> TripAdvice.FOUND
            }
            TripSummary(advice, youToDest, onSite)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Remembered map camera, so the map opens where you left it instead of zoomed out over the country.
    private val mapPrefs = app.getSharedPreferences("parkpal_map", Context.MODE_PRIVATE)
    val hasSavedCamera: Boolean = mapPrefs.contains("lat")
    var cameraLat = Double.fromBits(mapPrefs.getLong("lat", 39.5.toRawBits()))
    var cameraLon = Double.fromBits(mapPrefs.getLong("lon", (-98.35).toRawBits()))
    var cameraZoom = Double.fromBits(mapPrefs.getLong("zoom", 4.0.toRawBits()))
    var hasAutoCentered = false

    private val _suggesting = MutableStateFlow(false)
    val suggesting: StateFlow<Boolean> = _suggesting.asStateFlow()

    private var searchSeq = 0
    private var searchJob: Job? = null
    private var suggestJob: Job? = null
    private var routeJob: Job? = null
    private var lastQuery = ""

    fun setTab(tab: AppTab) {
        _tab.value = tab
    }

    fun toast(text: String) {
        _message.value = text
    }

    fun messageShown() {
        _message.value = null
    }

    fun setFilters(f: Filters) {
        _filters.value = f
    }

    fun saveCamera(lat: Double, lon: Double, zoom: Double) {
        cameraLat = lat
        cameraLon = lon
        cameraZoom = zoom
        mapPrefs.edit()
            .putLong("lat", lat.toRawBits())
            .putLong("lon", lon.toRawBits())
            .putLong("zoom", zoom.toRawBits())
            .apply()
    }

    fun consumeFocus() {
        _focus.value = null
    }

    fun consumeFit() {
        _fit.value = null
    }

    fun toggleSheet() {
        _sheetExpanded.update { !it }
    }

    /** Selects a parking place and loads the driving route to it. */
    fun select(place: ParkingPlace?) {
        _selected.value = place
        if (place != null) _selectedPoi.value = null
        loadRoute(place?.id, place?.lat ?: 0.0, place?.lon ?: 0.0)
    }

    /** The driving route to whatever is selected, for the line on the map and the drive time. */
    private fun loadRoute(id: String?, lat: Double, lon: Double) {
        routeJob?.cancel()
        _route.value = null
        _routeLoading.value = false
        if (id == null) return
        routeJob = viewModelScope.launch {
            val me = userLocation() ?: return@launch
            if (distanceMeters(me.first, me.second, lat, lon) < 150) return@launch
            _routeLoading.value = true
            try {
                val r = RouteApi.drive(me.first, me.second, lat, lon)
                if (r != null && selectedId() == id) {
                    _route.value = RouteUi(id, r.points, r.meters, r.seconds)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No route line; the rest of the card still works.
            } finally {
                if (selectedId() == id) _routeLoading.value = false
            }
        }
    }

    private fun selectedId(): String? = _selected.value?.id ?: _selectedPoi.value?.id

    /** Tap on empty map: close whichever card is open. */
    fun clearSelection() {
        if (_selectedPoi.value != null) selectPoi(null)
        if (_selected.value != null) select(null)
    }

    // ---- Explore (gas, food, coffee…) ----

    private val _exploreCategory = MutableStateFlow<PoiCategory?>(null)
    val exploreCategory: StateFlow<PoiCategory?> = _exploreCategory.asStateFlow()

    private val _pois = MutableStateFlow<List<Poi>>(emptyList())
    val pois: StateFlow<List<Poi>> = _pois.asStateFlow()

    private val _exploreLoading = MutableStateFlow(false)
    val exploreLoading: StateFlow<Boolean> = _exploreLoading.asStateFlow()

    private val _selectedPoi = MutableStateFlow<Poi?>(null)
    val selectedPoi: StateFlow<Poi?> = _selectedPoi.asStateFlow()

    private val _exploreExpanded = MutableStateFlow(true)
    val exploreExpanded: StateFlow<Boolean> = _exploreExpanded.asStateFlow()

    private var exploreJob: Job? = null
    private var exploreSeq = 0

    /** Category button tapped: show that kind of place around [lat],[lon]; tapping the open one closes it. */
    fun toggleExplore(category: PoiCategory, lat: Double, lon: Double) {
        if (_exploreCategory.value == category) closeExplore() else explore(category, lat, lon)
    }

    /** "This area": look again where the map is now. */
    fun exploreHere(lat: Double, lon: Double) {
        explore(_exploreCategory.value ?: return, lat, lon, fit = false)
    }

    private fun explore(category: PoiCategory, lat: Double, lon: Double, fit: Boolean = true) {
        exploreJob?.cancel()
        val seq = ++exploreSeq
        select(null)
        selectPoi(null)
        if (_exploreCategory.value != category) _pois.value = emptyList()
        _exploreCategory.value = category
        _exploreExpanded.value = true
        exploreJob = viewModelScope.launch {
            _exploreLoading.value = true
            try {
                val found = ExploreRepo.nearby(category, lat, lon)
                if (seq != exploreSeq) return@launch
                _pois.value = found
                if (found.isEmpty()) {
                    toast("No ${category.label.lowercase()} found around here")
                } else if (fit) {
                    _fit.value = listOf(lat to lon) + found.take(6).map { it.lat to it.lon }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq == exploreSeq) toast("Couldn't load ${category.label.lowercase()}: ${e.message ?: "network error"}")
            } finally {
                if (seq == exploreSeq) _exploreLoading.value = false
            }
        }
    }

    fun closeExplore() {
        exploreJob?.cancel()
        exploreSeq++
        _exploreLoading.value = false
        _exploreCategory.value = null
        _pois.value = emptyList()
        selectPoi(null)
    }

    fun toggleExploreSheet() {
        _exploreExpanded.update { !it }
    }

    fun selectPoi(poi: Poi?) {
        _selectedPoi.value = poi
        if (poi != null) {
            _selected.value = null
            hasAutoCentered = true
            _focus.value = MapFocus(poi.lat, poi.lon, maxOf(cameraZoom, 15.0))
        }
        loadRoute(poi?.id, poi?.lat ?: 0.0, poi?.lon ?: 0.0)
    }

    /** Drive straight to an Explore place. */
    fun goToPoi(poi: Poi) {
        // Somewhere you'd park and walk in: remember it, so parking afterwards is linked to it.
        val parkThere = poi.category != PoiCategory.GAS && poi.category != PoiCategory.REST
        if (parkThere) ParkStore.setTrip(poi.toDestination())
        Directions.driveTo(ctx, poi.toDestination(), offerParking = parkThere)
    }

    /** Use an Explore place as the destination, which finds parking around it. */
    fun parkingNearPoi(poi: Poi) {
        pickDestination(poi.toDestination())
        if (!settings.value.autoFindParking) findParkingNearDestination()
    }

    /** Adds an Explore place to your Dawarich places, so it shows up under "Your places". */
    fun savePoiToDawarich(poi: Poi) {
        val s = settings.value
        if (!Dawarich.configured(s)) {
            toast("Connect Dawarich in Settings to save places")
            return
        }
        viewModelScope.launch {
            try {
                val note = listOfNotNull("Added with ParkPal", poi.category.label, poi.address).joinToString(" · ")
                Dawarich.createPlace(s.dawarichUrl, s.dawarichKey, poi.name, poi.lat, poi.lon, note)
                toast("Saved ${poi.name} to Dawarich")
                DawarichSync.refreshPlaces(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't save to Dawarich: ${e.message ?: "network error"}")
            }
        }
    }

    /** Parking around a point. Saved results show instantly; fresh ones replace them when they arrive. */
    fun searchArea(lat: Double, lon: Double) {
        searchJob?.cancel()
        val seq = ++searchSeq
        select(null)
        searchJob = viewModelScope.launch {
            _loading.value = true
            try {
                var any = false
                ParkingRepo.nearby(lat, lon, settings.value.radiusMeters).collect { results ->
                    if (seq != searchSeq) return@collect
                    any = true
                    _places.value = results
                }
                if (seq == searchSeq && (!any || _places.value.isEmpty())) {
                    toast("No parking mapped here. Try a bigger search radius in Settings.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq == searchSeq) toast("Couldn't load parking: ${e.message ?: "network error"}")
            } finally {
                if (seq == searchSeq) _loading.value = false
            }
        }
    }

    // ---- Search box ----

    /** Live suggestions while typing, biased toward where the map is looking. */
    fun onQueryChange(text: String, biasLat: Double, biasLon: Double) {
        lastQuery = text
        suggestJob?.cancel()
        _suggesting.value = false
        val q = text.trim()
        // Your Dawarich places that match come first, with no wait.
        val mine = DawarichSync.matching(q)
        if (q.length < 3) {
            _suggestions.value = mine
            return
        }
        // Already looked this up (e.g. after a backspace): show it with no wait.
        Geo.cachedSuggestions(q, biasLat, biasLon)?.let {
            _suggestions.value = DawarichSync.withMine(mine, it)
            return
        }
        if (mine.isNotEmpty()) _suggestions.value = mine
        suggestJob = viewModelScope.launch {
            delay(250)
            _suggesting.value = true
            try {
                val results = Geo.suggest(q, biasLat, biasLon)
                if (lastQuery == text) _suggestions.value = DawarichSync.withMine(mine, results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Quietly keep the old suggestions; pressing Search tries again.
            } finally {
                if (lastQuery == text) _suggesting.value = false
            }
        }
    }

    /** Keyboard Search button: take the top suggestion, or run a full search. */
    fun submitQuery(text: String, biasLat: Double, biasLon: Double) {
        val q = text.trim()
        if (q.isEmpty()) return
        val pending = suggestJob?.isActive == true
        val first = _suggestions.value.firstOrNull()
        if (!pending && first != null && lastQuery == text) {
            pickDestination(first)
            return
        }
        suggestJob?.cancel()
        viewModelScope.launch {
            try {
                val results = Geo.search(q, biasLat, biasLon)
                when {
                    results.isEmpty() -> toast("No places found for \"$q\"")
                    results.size == 1 -> pickDestination(results[0])
                    else -> {
                        lastQuery = text
                        _suggestions.value = results
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Search failed: ${e.message ?: "network error"}")
            }
        }
    }

    fun clearRecents() = ParkStore.clearRecents()

    /** Your Dawarich places, shown when the search box is empty. */
    val dawarichPlaces: StateFlow<List<Destination>> = DawarichSync.places

    /** Search box tapped: freshen your Dawarich places if they're a few minutes old. */
    fun onSearchFocused() {
        viewModelScope.launch { DawarichSync.refreshPlaces() }
    }

    // ---- Trip ----

    fun pickDestination(d: Destination, remember: Boolean = true) {
        if (_exploreCategory.value != null) closeExplore()
        suggestJob?.cancel()
        _suggestions.value = emptyList()
        lastQuery = d.title
        if (remember) ParkStore.addRecent(d)
        searchJob?.cancel()
        searchSeq++
        _loading.value = false
        _places.value = emptyList()
        _searchedDest.value = false
        _sheetExpanded.value = true
        _destination.value = d
        select(null)
        _tab.value = AppTab.MAP
        hasAutoCentered = true
        _focus.value = MapFocus(d.lat, d.lon, 16.0)
        viewModelScope.launch { userLocation() }
        if (settings.value.autoFindParking) findParkingNearDestination()
    }

    /** Long-press on the map: use that point as the destination. */
    fun dropPin(lat: Double, lon: Double) {
        val pin = Destination("Dropped pin", Format.latLon(lat, lon), lat, lon)
        pickDestination(pin, remember = false)
        viewModelScope.launch {
            val address = Geo.reverse(ctx, lat, lon) ?: return@launch
            if (_destination.value == pin) _destination.value = pin.copy(subtitle = address)
        }
    }

    fun clearDestination() {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
        _suggesting.value = false
        lastQuery = ""
        searchJob?.cancel()
        searchSeq++
        _loading.value = false
        _destination.value = null
        _searchedDest.value = false
        _places.value = emptyList()
        select(null)
    }

    /**
     * Looks for parking around the destination. One search covers the wider area, then the list keeps to
     * your walking distance if there's enough parking that fits, or widens if there isn't.
     */
    fun findParkingNearDestination() {
        val dest = _destination.value ?: return
        _searchedDest.value = true
        searchJob?.cancel()
        val seq = ++searchSeq
        searchJob = viewModelScope.launch {
            _loading.value = true
            try {
                val s = settings.value
                val walk = s.walkRadiusMeters
                val wide = (walk * 2).coerceIn(walk, MAX_TRIP_RADIUS)
                var fitted = false
                ParkingRepo.nearby(dest.lat, dest.lon, wide).collect { results ->
                    if (seq != searchSeq) return@collect
                    val withDistance = results.map { it to distanceMeters(dest.lat, dest.lon, it.lat, it.lon) }
                    val fittingNear = withDistance.count { (p, d) ->
                        d <= walk && Clearance.fitStatus(p, s) != FitStatus.TOO_LOW
                    }
                    val radius = if (fittingNear >= 3) walk else wide
                    val inRange = withDistance.filter { it.second <= radius }.map { it.first }
                    _tripRadius.value = radius
                    _places.value = inRange
                    if (!fitted) {
                        fitted = true
                        requestFit(dest, inRange)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq == searchSeq) {
                    _searchedDest.value = false
                    toast("Couldn't load parking near ${dest.title}: ${e.message ?: "network error"}")
                }
            } finally {
                if (seq == searchSeq) _loading.value = false
            }
        }
    }

    private fun requestFit(dest: Destination, results: List<ParkingPlace>) {
        val s = settings.value
        val f = _filters.value
        val nearest = results
            .filter { !f.fitsOnly || Clearance.fitStatus(it, s) != FitStatus.TOO_LOW }
            .sortedBy { distanceMeters(dest.lat, dest.lon, it.lat, it.lon) }
            .take(5)
        _fit.value = listOf(dest.lat to dest.lon) + nearest.map { it.lat to it.lon }
    }

    /** Start driving to a parking place; on arrival, offer to save the spot and walk to the destination. */
    fun go(place: ParkingPlace) {
        val dest = _destination.value
        dest?.let { ParkStore.setTrip(it) }
        Directions.driveToParking(ctx, place, after = dest)
    }

    /** Skip parking and drive straight to the destination. */
    fun driveToDestination() {
        val dest = _destination.value ?: return
        ParkStore.setTrip(dest)
        Directions.driveTo(ctx, dest, offerParking = true)
    }

    /** Walking directions back to your truck. */
    fun walkToCar() {
        val s = ParkStore.active.value
        if (s == null) {
            _tab.value = AppTab.SPOT
            toast("No parking spot saved")
            return
        }
        Directions.walkTo(ctx, Destination(s.title(), s.address ?: "", s.lat, s.lon), toCar = true)
    }

    /** Walking directions from your truck to where you were going. */
    fun walkToDestination() {
        val s = ParkStore.active.value ?: return
        val name = s.destName ?: return
        Directions.walkTo(ctx, Destination(name, "", s.destLat ?: return, s.destLon ?: return))
    }

    /** Drive to one of your places; on arrival, offer to save where you parked. */
    fun driveToSaved(d: Destination) {
        ParkStore.setTrip(d)
        Directions.driveTo(ctx, d, offerParking = true)
    }

    /** Drive to a saved garage. */
    fun driveToPlace(place: ParkingPlace) = Directions.driveToParking(ctx, place, after = null)

    private suspend fun userLocation(): Pair<Double, Double>? {
        val cached = _userLoc.value
        if (cached != null && System.currentTimeMillis() - userLocAt < 60_000L) return cached
        val loc = LocationHelper.quick(ctx) ?: return cached
        val point = loc.latitude to loc.longitude
        _userLoc.value = point
        userLocAt = System.currentTimeMillis()
        return point
    }

    fun showOnMap(lat: Double, lon: Double) {
        hasAutoCentered = true
        _focus.value = MapFocus(lat, lon, 17.0)
        _tab.value = AppTab.MAP
    }

    /** Opens a saved favorite on the map with its details card. */
    fun showPlace(place: ParkingPlace) {
        if (_places.value.none { it.id == place.id }) _places.value = _places.value + place
        select(place)
        showOnMap(place.lat, place.lon)
    }

    fun parkHere() {
        _tab.value = AppTab.SPOT
        toast("Getting your location…")
        val dest = _destination.value ?: ParkStore.currentTrip()
        viewModelScope.launch {
            val session = ParkActions.parkHere(ctx, dest)
            toast(if (session == null) "Couldn't get your location. Check that location is allowed for ParkPal." else "Spot saved")
        }
    }

    fun parkAtPlace(place: ParkingPlace) {
        ParkActions.parkAt(ctx, place.lat, place.lon, place.name, _destination.value ?: ParkStore.currentTrip())
        select(null)
        _tab.value = AppTab.SPOT
        toast("Saved ${place.name} as your spot")
    }

    fun updateSpot(transform: (ParkingSession) -> ParkingSession) = ParkStore.updateActive(transform)

    fun setPhoto(path: String?) {
        val old = active.value?.photoPath
        ParkStore.updateActive { it.copy(photoPath = path) }
        if (old != null && old != path) ParkStore.deletePhoto(old)
    }

    fun startMeter(minutes: Int) = ParkActions.startMeter(ctx, minutes)

    fun extendMeter(minutes: Int) = ParkActions.extendMeter(ctx, minutes)

    fun clearMeter() = ParkActions.setMeter(ctx, null)

    fun endParking() {
        ParkActions.endParking(ctx)
        toast("Parking ended · saved to History")
    }

    fun toggleFavorite(place: ParkingPlace) = ParkStore.toggleFavorite(place)

    fun deleteHistory(id: Long) = ParkStore.deleteHistory(id)

    fun clearHistory() = ParkStore.clearHistory()

    // ---- In-app updates ----

    private val _update = MutableStateFlow(UpdateUi())
    val update: StateFlow<UpdateUi> = _update.asStateFlow()
    val currentBuild: Long by lazy { Updater.currentBuild(ctx) }
    private var downloadJob: Job? = null
    private var installWhenAllowed = false

    /** Looks for a newer build. On launch it stays quiet; from Settings it reports "up to date" too. */
    fun checkForUpdate(manual: Boolean) {
        val state = _update.value
        if (state.checking || state.progress != null) return
        _update.update { it.copy(checking = true) }
        viewModelScope.launch {
            try {
                val info = Updater.check(ctx)
                when {
                    info == null -> {
                        _update.value = UpdateUi()
                        if (manual) toast("ParkPal is up to date (build $currentBuild)")
                    }
                    !manual && Updater.isSkipped(ctx, info.build) ->
                        _update.update { it.copy(checking = false, info = info) }
                    else -> _update.update {
                        it.copy(checking = false, info = info, showDialog = true, error = null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _update.update { it.copy(checking = false) }
                if (manual) toast("Couldn't check for updates: ${e.message ?: "network error"}")
            }
        }
    }

    fun showUpdateDialog() {
        if (_update.value.info != null) _update.update { it.copy(showDialog = true) }
    }

    fun startUpdate() {
        val info = _update.value.info ?: return
        val ready = _update.value.apk
        if (ready != null && ready.exists()) {
            installApk(ready)
            return
        }
        if (downloadJob?.isActive == true) return
        _update.update { it.copy(progress = 0f, error = null) }
        downloadJob = viewModelScope.launch {
            try {
                val file = Updater.download(ctx, info) { p -> _update.update { it.copy(progress = p) } }
                _update.update { it.copy(progress = null, apk = file) }
                installApk(file)
            } catch (e: CancellationException) {
                _update.update { it.copy(progress = null) }
                throw e
            } catch (e: Exception) {
                _update.update { it.copy(progress = null, error = "Download failed: ${e.message ?: "network error"}") }
            }
        }
    }

    fun dismissUpdate(skipThisBuild: Boolean) {
        downloadJob?.cancel()
        installWhenAllowed = false
        val info = _update.value.info
        if (skipThisBuild && info != null) Updater.skip(ctx, info.build)
        _update.update { it.copy(showDialog = false, progress = null, needsPermission = false) }
    }

    /** Called when the app comes back to the front, e.g. after allowing installs in Settings. */
    fun onAppResumed() {
        val apk = _update.value.apk
        if (installWhenAllowed && apk != null && Updater.canInstall(ctx)) installApk(apk)
    }

    private fun installApk(file: File) {
        if (!Updater.canInstall(ctx)) {
            installWhenAllowed = true
            _update.update { it.copy(needsPermission = true) }
            Updater.openInstallPermission(ctx)
            return
        }
        installWhenAllowed = false
        _update.update { it.copy(needsPermission = false) }
        Updater.install(ctx, file)
    }

    companion object {
        /** Parking this close to the destination counts as "on site". */
        const val ON_SITE_METERS = 100.0
        /** If you're this close already, walking is an option. */
        const val WALKABLE_METERS = 800.0
        const val MAX_TRIP_RADIUS = 1600
    }
}

data class UpdateUi(
    val info: UpdateInfo? = null,
    val showDialog: Boolean = false,
    val checking: Boolean = false,
    /** Download progress from 0 to 1, or null when not downloading. */
    val progress: Float? = null,
    val apk: File? = null,
    val needsPermission: Boolean = false,
    val error: String? = null,
)
