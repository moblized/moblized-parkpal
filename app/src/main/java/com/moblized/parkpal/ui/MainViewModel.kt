package com.moblized.parkpal.ui

import android.app.Application
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.moblized.parkpal.data.AppSettings
import com.moblized.parkpal.data.Clearance
import com.moblized.parkpal.data.FeeInfo
import com.moblized.parkpal.data.FitStatus
import com.moblized.parkpal.data.Geo
import com.moblized.parkpal.data.GeoResult
import com.moblized.parkpal.data.OverpassApi
import com.moblized.parkpal.data.ParkActions
import com.moblized.parkpal.data.ParkStore
import com.moblized.parkpal.data.ParkingPlace
import com.moblized.parkpal.data.ParkingSession
import com.moblized.parkpal.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppTab(val label: String, val icon: ImageVector) {
    MAP("Find", Icons.Filled.Map),
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

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Application get() = getApplication()

    val settings: StateFlow<AppSettings> = SettingsStore.settings
    val active: StateFlow<ParkingSession?> = ParkStore.active
    val favorites: StateFlow<List<ParkingPlace>> = ParkStore.favorites
    val history: StateFlow<List<ParkingSession>> = ParkStore.history

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

    private val _geoResults = MutableStateFlow<List<GeoResult>>(emptyList())
    val geoResults: StateFlow<List<GeoResult>> = _geoResults.asStateFlow()

    private val _focus = MutableStateFlow<MapFocus?>(null)
    val focus: StateFlow<MapFocus?> = _focus.asStateFlow()

    // Remembered map camera, so switching tabs doesn't reset the map.
    var cameraLat = 39.5
    var cameraLon = -98.35
    var cameraZoom = 4.0
    var hasAutoCentered = false

    private var searchSeq = 0

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

    fun select(place: ParkingPlace?) {
        _selected.value = place
    }

    fun saveCamera(lat: Double, lon: Double, zoom: Double) {
        cameraLat = lat
        cameraLon = lon
        cameraZoom = zoom
    }

    fun consumeFocus() {
        _focus.value = null
    }

    fun searchArea(lat: Double, lon: Double) {
        val seq = ++searchSeq
        viewModelScope.launch {
            _loading.value = true
            try {
                val results = OverpassApi.nearby(lat, lon, settings.value.radiusMeters)
                if (seq != searchSeq) return@launch
                _places.value = results
                _selected.value = null
                if (results.isEmpty()) toast("No parking mapped here. Try a bigger search radius in Settings.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (seq == searchSeq) toast("Couldn't load parking: ${e.message ?: "network error"}")
            } finally {
                if (seq == searchSeq) _loading.value = false
            }
        }
    }

    fun searchPlace(query: String) {
        viewModelScope.launch {
            try {
                val results = Geo.search(query, cameraLat, cameraLon)
                _geoResults.value = results
                if (results.isEmpty()) toast("No places found for \"$query\"")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Search failed: ${e.message ?: "network error"}")
            }
        }
    }

    fun clearGeoResults() {
        _geoResults.value = emptyList()
    }

    fun pickGeo(result: GeoResult) {
        _geoResults.value = emptyList()
        hasAutoCentered = true
        _focus.value = MapFocus(result.lat, result.lon, 16.0)
        searchArea(result.lat, result.lon)
    }

    fun showOnMap(lat: Double, lon: Double) {
        hasAutoCentered = true
        _focus.value = MapFocus(lat, lon, 17.0)
        _tab.value = AppTab.MAP
    }

    /** Opens a saved favorite on the map with its details card. */
    fun showPlace(place: ParkingPlace) {
        if (_places.value.none { it.id == place.id }) _places.value = _places.value + place
        _selected.value = place
        showOnMap(place.lat, place.lon)
    }

    fun parkHere() {
        _tab.value = AppTab.SPOT
        toast("Getting your location…")
        viewModelScope.launch {
            val session = ParkActions.parkHere(ctx)
            toast(if (session == null) "Couldn't get your location. Check that location is allowed for ParkPal." else "Spot saved")
        }
    }

    fun parkAtPlace(place: ParkingPlace) {
        ParkActions.parkAt(ctx, place.lat, place.lon, place.name)
        _selected.value = null
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
}
