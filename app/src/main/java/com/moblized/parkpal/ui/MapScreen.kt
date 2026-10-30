@file:OptIn(ExperimentalMaterial3Api::class)

package com.moblized.parkpal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moblized.parkpal.data.Clearance
import com.moblized.parkpal.data.GeoResult
import com.moblized.parkpal.ui.theme.ParkColors
import com.moblized.parkpal.util.Format
import com.moblized.parkpal.util.LocationHelper
import com.moblized.parkpal.util.Nav
import com.moblized.parkpal.util.PayApps
import com.moblized.parkpal.util.distanceMeters
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

@Composable
fun MapScreen(vm: MainViewModel, dark: Boolean, hasLocation: Boolean) {
    val ctx = LocalContext.current
    val visible by vm.visiblePlaces.collectAsStateWithLifecycle()
    val total by vm.totalFound.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val geoResults by vm.geoResults.collectAsStateWithLifecycle()
    val focus by vm.focus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val icons = remember { MarkerIcons(ctx) }

    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setDestroyMode(false)
            setTilesScaledToDpi(true)
            setMinZoomLevel(3.0)
            setMaxZoomLevel(20.0)
            controller.setZoom(vm.cameraZoom)
            controller.setCenter(GeoPoint(vm.cameraLat, vm.cameraLon))
        }
    }
    val myLocation = remember {
        MyLocationNewOverlay(GpsMyLocationProvider(ctx), mapView).also { mapView.overlays.add(it) }
    }
    remember {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                vm.select(null)
                vm.clearGeoResults()
                return false
            }

            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }
        MapEventsOverlay(receiver).also { mapView.overlays.add(0, it) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    if (LocationHelper.hasPermission(ctx)) myLocation.enableMyLocation()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    myLocation.disableMyLocation()
                    mapView.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val c = mapView.mapCenter
            vm.saveCamera(c.latitude, c.longitude, mapView.zoomLevelDouble)
            myLocation.disableMyLocation()
            mapView.onDetach()
        }
    }

    // First time we have location: jump to it and look for parking nearby.
    LaunchedEffect(hasLocation) {
        if (!hasLocation) return@LaunchedEffect
        myLocation.enableMyLocation()
        if (!vm.hasAutoCentered) {
            vm.hasAutoCentered = true
            val loc = LocationHelper.current(ctx)
            if (loc != null) {
                mapView.controller.setZoom(16.0)
                mapView.controller.setCenter(GeoPoint(loc.latitude, loc.longitude))
                vm.searchArea(loc.latitude, loc.longitude)
            }
        }
    }

    LaunchedEffect(focus) {
        val f = focus ?: return@LaunchedEffect
        mapView.controller.setZoom(f.zoom)
        mapView.controller.setCenter(GeoPoint(f.lat, f.lon))
        vm.consumeFocus()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { mv ->
                mv.overlays.filterIsInstance<Marker>().forEach { mv.overlays.remove(it) }
                val selectedId = selected?.id
                visible.forEach { (place, status) ->
                    val marker = Marker(mv)
                    marker.position = GeoPoint(place.lat, place.lon)
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    marker.icon = icons.forPlace(place, status, place.id == selectedId)
                    marker.title = place.name
                    marker.setOnMarkerClickListener { _, _ ->
                        vm.select(place)
                        true
                    }
                    mv.overlays.add(marker)
                }
                active?.let { spot ->
                    val marker = Marker(mv)
                    marker.position = GeoPoint(spot.lat, spot.lon)
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    marker.icon = icons.parked()
                    marker.title = "Your spot"
                    marker.setOnMarkerClickListener { _, _ ->
                        vm.setTab(AppTab.SPOT)
                        true
                    }
                    mv.overlays.add(marker)
                }
                mv.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
                mv.invalidate()
            },
        )

        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlaceSearchField(onSearch = vm::searchPlace, onClear = vm::clearGeoResults)
            if (geoResults.isNotEmpty()) GeoResultsCard(geoResults, onPick = vm::pickGeo)
            FilterRow(filters, settings.vehicleName, onChange = vm::setFilters)
            if (total > 0) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
                    shadowElevation = 2.dp,
                ) {
                    Text(
                        text = if (visible.size == total) "$total places found" else "${visible.size} of $total shown · filters on",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Legend()
                Spacer(Modifier.weight(1f))
                SmallFloatingActionButton(
                    onClick = {
                        val here = myLocation.myLocation
                        if (here != null) {
                            mapView.controller.animateTo(here)
                        } else {
                            scope.launch {
                                val loc = LocationHelper.current(ctx)
                                if (loc != null) {
                                    mapView.controller.animateTo(GeoPoint(loc.latitude, loc.longitude))
                                } else {
                                    vm.toast("Location unavailable. Allow location for ParkPal.")
                                }
                            }
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Icon(Icons.Filled.MyLocation, contentDescription = "My location")
                }
            }
            val sel = selected
            if (sel != null) {
                val status = Clearance.fitStatus(sel, settings)
                val distance = myLocation.myLocation?.let {
                    Format.distance(distanceMeters(it.latitude, it.longitude, sel.lat, sel.lon))
                }
                PlaceCard(
                    place = sel,
                    status = status,
                    settings = settings,
                    isFavorite = favorites.any { it.id == sel.id },
                    distance = distance,
                    onClose = { vm.select(null) },
                    onNavigate = { Nav.navigate(ctx, sel.lat, sel.lon, walking = false) },
                    onPark = { vm.parkAtPlace(sel) },
                    onFavorite = { vm.toggleFavorite(sel) },
                    onPay = { PayApps.open(ctx, it) },
                )
            } else {
                ExtendedFloatingActionButton(
                    onClick = {
                        val c = mapView.mapCenter
                        vm.searchArea(c.latitude, c.longitude)
                    },
                    icon = {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Search, contentDescription = null)
                        }
                    },
                    text = { Text(if (loading) "Searching…" else "Search this area") },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }

        // OpenStreetMap requires this credit on the map.
        Text(
            text = "© OpenStreetMap contributors",
            style = MaterialTheme.typography.labelSmall,
            color = Color.DarkGray,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 4.dp, bottom = 2.dp)
                .background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun PlaceSearchField(onSearch: (String) -> Unit, onClear: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    Surface(
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search a place or address") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = {
                        text = ""
                        onClear()
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (text.isNotBlank()) onSearch(text.trim())
                focusManager.clearFocus()
            }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun GeoResultsCard(results: List<GeoResult>, onPick: (GeoResult) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column {
            results.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider()
                ListItem(
                    headlineContent = { Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(r.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(Icons.Filled.Place, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onPick(r) },
                )
            }
        }
    }
}

@Composable
private fun FilterRow(f: Filters, vehicleName: String, onChange: (Filters) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MapChip("Fits my $vehicleName", f.fitsOnly) { onChange(f.copy(fitsOnly = !f.fitsOnly)) }
        MapChip("Free", f.freeOnly) { onChange(f.copy(freeOnly = !f.freeOnly)) }
        MapChip("Garages", f.type == TypeFilter.COVERED) {
            onChange(f.copy(type = if (f.type == TypeFilter.COVERED) TypeFilter.ALL else TypeFilter.COVERED))
        }
        MapChip("Lots & street", f.type == TypeFilter.OPEN) {
            onChange(f.copy(type = if (f.type == TypeFilter.OPEN) TypeFilter.ALL else TypeFilter.OPEN))
        }
    }
}

@Composable
private fun MapChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        } else {
            null
        },
        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = FilterChipDefaults.filterChipElevation(elevation = 2.dp),
    )
}

@Composable
private fun Legend() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LegendItem(ParkColors.Free, "Free")
            LegendItem(ParkColors.Paid, "Paid / fee not listed")
            LegendItem(ParkColors.Check, "Check height sign")
            LegendItem(ParkColors.TooLow, "Too low")
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
