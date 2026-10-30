@file:OptIn(ExperimentalMaterial3Api::class)

package com.moblized.parkpal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import com.moblized.parkpal.data.ParkingPlace
import com.moblized.parkpal.ui.theme.ParkColors
import com.moblized.parkpal.util.Format
import com.moblized.parkpal.util.LocationHelper
import com.moblized.parkpal.util.PayApps
import com.moblized.parkpal.util.distanceMeters
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

private enum class BottomMode { PLACE, TRIP, AREA }

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
    val suggesting by vm.suggesting.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val icons = remember { MarkerIcons(ctx) }

    var searchText by rememberSaveable { mutableStateOf("") }
    var searchFocused by remember { mutableStateOf(false) }

    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(if (dark) MapTiles.dark else MapTiles.light)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setDestroyMode(false)
            setTilesScaledToDpi(true)
            setMinZoomLevel(3.0)
            setMaxZoomLevel(20.0)
            // Open where you left off, so the map is ready immediately.
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
    val layers = remember {
        MapLayers(
            map = mapView,
            icons = icons,
            density = density,
            onPlace = { vm.select(it) },
            onParked = { vm.setTab(AppTab.SPOT) },
            onDestination = { vm.select(null) },
        )
    }

    LaunchedEffect(dark) {
        val source = if (dark) MapTiles.dark else MapTiles.light
        if (mapView.tileProvider.tileSource != source) mapView.setTileSource(source)
    }

    // Keep the search box showing the destination's name.
    LaunchedEffect(destination) {
        val d = destination
        searchText = d?.title ?: ""
        if (d != null) focusManager.clearFocus()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        fun saveCamera() {
            val c = mapView.mapCenter
            vm.saveCamera(c.latitude, c.longitude, mapView.zoomLevelDouble)
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    if (LocationHelper.hasPermission(ctx)) myLocation.enableMyLocation()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    saveCamera()
                    myLocation.disableMyLocation()
                    mapView.onPause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            saveCamera()
            myLocation.disableMyLocation()
            mapView.onDetach()
        }
    }

    // On launch: jump to your last known position right away (no waiting for GPS), show parking there
    // (instant if you've been there before), then correct once a fresh GPS fix arrives if you've moved.
    LaunchedEffect(hasLocation) {
        if (!hasLocation) return@LaunchedEffect
        myLocation.enableMyLocation()
        if (vm.hasAutoCentered) return@LaunchedEffect
        vm.hasAutoCentered = true
        fun goTo(lat: Double, lon: Double) {
            val target = GeoPoint(lat, lon)
            if (vm.hasSavedCamera) {
                mapView.controller.animateTo(target, 16.0, 600L)
            } else {
                mapView.controller.setZoom(16.0)
                mapView.controller.setCenter(target)
            }
            vm.searchArea(lat, lon)
        }
        val last = LocationHelper.last(ctx)
        if (last != null && vm.destination.value == null) goTo(last.latitude, last.longitude)
        val stale = last == null || System.currentTimeMillis() - last.time > 5 * 60_000L
        if (stale) {
            val fresh = LocationHelper.current(ctx) ?: return@LaunchedEffect
            val moved = last == null || distanceMeters(last.latitude, last.longitude, fresh.latitude, fresh.longitude) > 400
            if (moved && vm.destination.value == null && vm.selected.value == null) goTo(fresh.latitude, fresh.longitude)
        }
    }

    LaunchedEffect(focus) {
        val f = focus ?: return@LaunchedEffect
        vm.consumeFocus()
        mapView.controller.animateTo(GeoPoint(f.lat, f.lon), f.zoom, 700L)
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
            update = { _ ->
                val sel = selected
                val selPair = sel?.let { s ->
                    visible.firstOrNull { it.first.id == s.id } ?: (s to Clearance.fitStatus(s, settings))
                }
                layers.sync(visible, selPair, route, destination, active, dark)
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
                busy = suggesting,
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
            AnimatedVisibility(
                visible = showSuggestions || showRecents,
                enter = fadeIn(tween(150)) + expandVertically(tween(180)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(150)),
            ) {
                if (showRecents) {
                    PlaceList(recents, isRecent = true, onPick = vm::pickDestination, onClearRecents = vm::clearRecents)
                } else {
                    PlaceList(suggestions, isRecent = false, onPick = vm::pickDestination, onClearRecents = null)
                }
            }
            if (!showSuggestions && !showRecents) {
                FilterRow(filters, settings.vehicleName, onChange = vm::setFilters)
                AnimatedVisibility(visible = destination == null && total > 0, enter = fadeIn(), exit = fadeOut()) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
                        shadowElevation = 2.dp,
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (loading) {
                                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = if (visible.size == total) "$total places found" else "${visible.size} of $total shown · filters on",
                                style = MaterialTheme.typography.labelMedium,
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
                AnimatedVisibility(visible = destination == null && selected == null, enter = fadeIn(), exit = fadeOut()) {
                    Legend()
                }
                Spacer(Modifier.weight(1f))
                SmallFloatingActionButton(
                    onClick = {
                        val here = myLocation.myLocation
                        if (here != null) {
                            mapView.controller.animateTo(here)
                        } else {
                            scope.launch {
                                val loc = LocationHelper.quick(ctx)
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

            // Remember the last shown values so cards can animate out after they're cleared.
            val lastSel = remember { arrayOfNulls<ParkingPlace>(1) }
            val lastDest = remember { arrayOfNulls<Destination>(1) }
            val lastSummary = remember { arrayOfNulls<TripSummary>(1) }
            selected?.let { lastSel[0] = it }
            destination?.let { lastDest[0] = it }
            tripSummary?.let { lastSummary[0] = it }

            val mode = when {
                selected != null -> BottomMode.PLACE
                destination != null && tripSummary != null -> BottomMode.TRIP
                else -> BottomMode.AREA
            }
            AnimatedContent(
                targetState = mode,
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter,
                transitionSpec = {
                    (slideInVertically(tween(260)) { it / 4 } + fadeIn(tween(220))) togetherWith
                        (slideOutVertically(tween(180)) { it / 4 } + fadeOut(tween(150))) using
                        SizeTransform(clip = false)
                },
                label = "bottom card",
            ) { m ->
                when (m) {
                    BottomMode.PLACE -> {
                        val sel = selected ?: lastSel[0]
                        if (sel != null) {
                            val dest = destination
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
                    }
                    BottomMode.TRIP -> {
                        val dest = destination ?: lastDest[0]
                        val summary = tripSummary ?: lastSummary[0]
                        if (dest != null && summary != null) {
                            TripSheet(
                                dest = dest,
                                summary = summary,
                                items = tripItems,
                                radiusMeters = tripRadius,
                                expanded = sheetExpanded,
                                loading = loading,
                                vehicleName = settings.vehicleName,
                                onToggle = vm::toggleSheet,
                                onSelect = { place -> vm.select(place) },
                                onDrive = vm::driveToDestination,
                                onFindParking = vm::findParkingNearDestination,
                            )
                        }
                    }
                    BottomMode.AREA -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
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
                        )
                    }
                }
            }
        }

        // OpenStreetMap and CARTO require this credit on the map.
        Text(
            text = MapTiles.ATTRIBUTION,
            style = MaterialTheme.typography.labelSmall,
            color = if (dark) Color.LightGray else Color.DarkGray,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 4.dp, bottom = 2.dp)
                .background(
                    if (dark) Color.Black.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.75f),
                    RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 4.dp),
        )
    }
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
    busy: Boolean,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    if (text.isNotEmpty()) {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear")
                        }
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
