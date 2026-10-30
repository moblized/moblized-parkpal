@file:OptIn(ExperimentalMaterial3Api::class)

package com.moblized.parkpal.ui

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PathEffect
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.History
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.moblized.parkpal.data.Destination
import com.moblized.parkpal.ui.theme.ParkColors
import com.moblized.parkpal.util.Format
import com.moblized.parkpal.util.LocationHelper
import com.moblized.parkpal.util.PayApps
import com.moblized.parkpal.util.distanceMeters
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

@Composable
fun MapScreen(vm: MainViewModel, dark: Boolean, hasLocation: Boolean) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val focusManager = LocalFocusManager.current
    val visible by vm.visiblePlaces.collectAsStateWithLifecycle()
    val total by vm.totalFound.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val focus by vm.focus.collectAsStateWithLifecycle()
    val fit by vm.fit.collectAsStateWithLifecycle()
    val destination by vm.destination.collectAsStateWithLifecycle()
    val tripItems by vm.tripItems.collectAsStateWithLifecycle()
    val tripSummary by vm.tripSummary.collectAsStateWithLifecycle()
    val tripRadius by vm.tripRadius.collectAsStateWithLifecycle()
    val sheetExpanded by vm.sheetExpanded.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val routeLoading by vm.routeLoading.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val icons = remember { MarkerIcons(ctx) }

    var searchText by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }

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
                focusManager.clearFocus()
                return false
            }

            // Long-press anywhere to use that spot as your destination, like dropping a pin in Google Maps.
            override fun longPressHelper(p: GeoPoint?): Boolean {
                if (p == null) return false
                focusManager.clearFocus()
                vm.dropPin(p.latitude, p.longitude)
                return true
            }
        }
        MapEventsOverlay(receiver).also { mapView.overlays.add(0, it) }
    }

    // Keep the search box showing the destination's name.
    LaunchedEffect(destination) {
        val d = destination
        searchText = d?.title ?: ""
        if (d != null) focusManager.clearFocus()
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
            if (loc != null && vm.destination.value == null) {
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

    LaunchedEffect(fit) {
        val pts = fit ?: return@LaunchedEffect
        vm.consumeFit()
        fitMap(mapView, pts, density)
    }

    BackHandler(enabled = searchFocused || selected != null || destination != null) {
        when {
            searchFocused -> focusManager.clearFocus()
            selected != null -> vm.select(null)
            else -> vm.clearDestination()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { mv ->
                mv.overlays.filter { it is Marker || it is Polyline }.forEach { mv.overlays.remove(it) }
                val sel = selected
                val dest = destination
                val r = route
                // Driving route to the selected parking, drawn with a casing so it stands out.
                if (sel != null && r != null && r.placeId == sel.id && r.points.size > 1) {
                    val pts = r.points.map { GeoPoint(it.first, it.second) }
                    val casing = if (dark) 0xFF0B1220.toInt() else android.graphics.Color.WHITE
                    val color = if (dark) 0xFF7FA2FF.toInt() else 0xFF1F4FD8.toInt()
                    mv.overlays.add(line(mv, pts, casing, 10f * density, null))
                    mv.overlays.add(line(mv, pts, color, 6f * density, null))
                }
                // Dotted walking line from the parking to the destination.
                if (sel != null && dest != null) {
                    mv.overlays.add(
                        line(
                            mv,
                            listOf(GeoPoint(sel.lat, sel.lon), GeoPoint(dest.lat, dest.lon)),
                            0xFF6B7280.toInt(),
                            4f * density,
                            DashPathEffect(floatArrayOf(1f * density, 8f * density), 0f),
                        ),
                    )
                }
                val selectedId = sel?.id
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
                dest?.let { d ->
                    val marker = Marker(mv)
                    marker.position = GeoPoint(d.lat, d.lon)
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    marker.icon = icons.destination()
                    marker.title = d.title
                    marker.setOnMarkerClickListener { _, _ ->
                        vm.select(null)
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
            WhereToBar(
                text = searchText,
                hasDestination = destination != null,
                onTextChange = { t ->
                    searchText = t
                    val c = mapView.mapCenter
                    vm.onQueryChange(t, c.latitude, c.longitude)
                },
                onSearch = {
                    val c = mapView.mapCenter
                    vm.submitQuery(searchText, c.latitude, c.longitude)
                },
                onBack = {
                    focusManager.clearFocus()
                    vm.clearDestination()
                },
                onClear = {
                    if (destination != null) {
                        vm.clearDestination()
                    } else {
                        searchText = ""
                        vm.onQueryChange("", 0.0, 0.0)
                    }
                },
                onFocusChange = { searchFocused = it },
            )
            val showSuggestions = searchFocused && searchText.trim().length >= 3 && suggestions.isNotEmpty()
            val showRecents = searchFocused && searchText.isBlank() && recents.isNotEmpty()
            when {
                showSuggestions -> PlaceList(suggestions, isRecent = false, onPick = vm::pickDestination, onClearRecents = null)
                showRecents -> PlaceList(recents, isRecent = true, onPick = vm::pickDestination, onClearRecents = vm::clearRecents)
                else -> {
                    FilterRow(filters, settings.vehicleName, onChange = vm::setFilters)
                    if (destination == null && total > 0) {
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
                if (destination == null && selected == null) Legend()
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
            val dest = destination
            val summary = tripSummary
            when {
                sel != null -> {
                    val status = Clearance.fitStatus(sel, settings)
                    val distance = myLocation.myLocation?.let {
                        Format.distance(distanceMeters(it.latitude, it.longitude, sel.lat, sel.lon))
                    }
                    val r = route
                    val extra = buildList<Pair<ImageVector, String>> {
                        if (dest != null) {
                            val walk = distanceMeters(sel.lat, sel.lon, dest.lat, dest.lon)
                            add(Icons.Filled.DirectionsWalk to "${Format.walk(walk)} to ${dest.title}")
                        }
                        if (r != null && r.placeId == sel.id) {
                            add(Icons.Filled.DirectionsCar to "About ${Format.driveTime(r.seconds)} drive · ${Format.distance(r.meters)}")
                        } else if (routeLoading) {
                            add(Icons.Filled.DirectionsCar to "Finding route…")
                        }
                    }
                    PlaceCard(
                        place = sel,
                        status = status,
                        settings = settings,
                        isFavorite = favorites.any { it.id == sel.id },
                        distance = distance,
                        onClose = { vm.select(null) },
                        onNavigate = { vm.go(sel) },
                        onPark = { vm.parkAtPlace(sel) },
                        onFavorite = { vm.toggleFavorite(sel) },
                        onPay = { PayApps.open(ctx, it) },
                        extraLines = extra,
                        goLabel = "Go",
                    )
                }
                dest != null && summary != null -> TripSheet(
                    dest = dest,
                    summary = summary,
                    items = tripItems,
                    radiusMeters = tripRadius,
                    expanded = sheetExpanded,
                    vehicleName = settings.vehicleName,
                    onToggle = vm::toggleSheet,
                    onSelect = { place -> vm.select(place) },
                    onDrive = vm::driveToDestination,
                    onFindParking = vm::findParkingNearDestination,
                )
                else -> ExtendedFloatingActionButton(
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

private fun line(mv: MapView, pts: List<GeoPoint>, color: Int, width: Float, effect: PathEffect?): Polyline =
    Polyline(mv).apply {
        setPoints(pts)
        outlinePaint.color = color
        outlinePaint.strokeWidth = width
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        outlinePaint.isAntiAlias = true
        outlinePaint.pathEffect = effect
        setOnClickListener { _, _, _ -> true }
    }

/** Zooms so all points show, leaving room for the search bar on top and the list at the bottom. */
private fun fitMap(mv: MapView, pts: List<Pair<Double, Double>>, density: Float) {
    if (pts.isEmpty()) return
    var north = pts.maxOf { it.first }
    var south = pts.minOf { it.first }
    var east = pts.maxOf { it.second }
    var west = pts.minOf { it.second }
    val minSpan = 0.003
    if (north - south < minSpan) {
        val c = (north + south) / 2
        north = c + minSpan / 2
        south = c - minSpan / 2
    }
    if (east - west < minSpan) {
        val c = (east + west) / 2
        east = c + minSpan / 2
        west = c - minSpan / 2
    }
    val latSpan = north - south
    val lonSpan = east - west
    val box = BoundingBox(north + latSpan * 0.45, east + lonSpan * 0.12, south - latSpan * 1.1, west - lonSpan * 0.12)
    val border = (16 * density).toInt()
    if (mv.width > 0 && mv.height > 0) {
        mv.zoomToBoundingBox(box, true, border)
    } else {
        mv.addOnFirstLayoutListener { _, _, _, _, _ -> mv.zoomToBoundingBox(box, false, border) }
    }
}

@Composable
private fun WhereToBar(
    text: String,
    hasDestination: Boolean,
    onTextChange: (String) -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        TextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onFocusChange(it.isFocused) },
            placeholder = { Text("Where to?") },
            leadingIcon = {
                if (hasDestination) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    Icon(Icons.Filled.Search, contentDescription = null)
                }
            },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        )
    }
}

/** Suggestions while typing, or recent destinations when the box is empty. */
@Composable
private fun PlaceList(
    places: List<Destination>,
    isRecent: Boolean,
    onPick: (Destination) -> Unit,
    onClearRecents: (() -> Unit)?,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        LazyColumn(Modifier.heightIn(max = 340.dp)) {
            if (isRecent) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Recent",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (onClearRecents != null) TextButton(onClick = onClearRecents) { Text("Clear") }
                    }
                }
            }
            items(places, key = { "${it.title}|${it.lat}|${it.lon}" }) { d ->
                ListItem(
                    headlineContent = { Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = if (d.subtitle.isNotBlank()) {
                        { Text(d.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    } else {
                        null
                    },
                    leadingContent = {
                        Icon(if (isRecent) Icons.Filled.History else Icons.Filled.Place, contentDescription = null)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onPick(d) },
                )
                HorizontalDivider()
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
