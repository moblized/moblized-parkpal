@file:OptIn(ExperimentalMaterial3Api::class)

package com.moblized.parkpal.ui

import android.app.Application
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moblized.parkpal.data.Dawarich
import com.moblized.parkpal.data.DawarichSync
import com.moblized.parkpal.data.SettingsStore
import com.moblized.parkpal.data.TimelineApi
import com.moblized.parkpal.data.TimelineDay
import com.moblized.parkpal.data.TimelineEntry
import com.moblized.parkpal.util.Format
import com.moblized.parkpal.util.distanceMeters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed class DayState {
    data object NotConnected : DayState()
    data object Loading : DayState()
    data class Loaded(val day: TimelineDay) : DayState()
    data class Failed(val message: String) : DayState()
}

class TimelineViewModel(app: Application) : AndroidViewModel(app) {
    private val _date = MutableStateFlow(LocalDate.now())
    val date: StateFlow<LocalDate> = _date.asStateFlow()

    private val _state = MutableStateFlow<DayState>(DayState.Loading)
    val state: StateFlow<DayState> = _state.asStateFlow()

    private val cache = HashMap<LocalDate, TimelineDay>()
    private var job: Job? = null

    fun setDate(d: LocalDate) {
        if (d.isAfter(LocalDate.now())) return
        _date.value = d
        load()
    }

    fun previous() = setDate(_date.value.minusDays(1))

    fun next() = setDate(_date.value.plusDays(1))

    fun refresh() {
        cache.remove(_date.value)
        load()
    }

    /** Shows the selected day: instantly from memory if seen before (today is always refreshed). */
    fun load() {
        val s = SettingsStore.settings.value
        if (!Dawarich.configured(s)) {
            _state.value = DayState.NotConnected
            return
        }
        val d = _date.value
        val cached = cache[d]
        if (cached != null) {
            _state.value = DayState.Loaded(cached)
            if (d != LocalDate.now()) return
        } else {
            _state.value = DayState.Loading
        }
        // Send any recorded points first so today is up to date.
        DawarichSync.kick(getApplication())
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val day = TimelineApi.day(s.dawarichUrl, s.dawarichKey, d)
                cache[d] = day
                if (_date.value == d) _state.value = DayState.Loaded(day)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_date.value == d && cache[d] == null) {
                    _state.value = DayState.Failed(e.message ?: "Couldn't reach Dawarich")
                }
            }
        }
    }
}

@Composable
fun TimelineScreen(dark: Boolean) {
    val vm: TimelineViewModel = viewModel()
    val date by vm.date.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by SettingsStore.settings.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf<List<Pair<Double, Double>>?>(null) }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(date) { focus = null }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        DateBar(
            date = date,
            onPrevious = vm::previous,
            onNext = vm::next,
            onPick = { showPicker = true },
            onRefresh = vm::refresh,
        )
        when (val st = state) {
            DayState.NotConnected -> EmptyState(
                Icons.Filled.Timeline,
                "Connect Dawarich",
                "Add your Dawarich address and API key in Settings → Dawarich to see your timeline here.",
            )
            DayState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is DayState.Failed -> EmptyState(Icons.Filled.CloudOff, "Couldn't load your timeline", st.message)
            is DayState.Loaded -> {
                val day = st.day
                val visits = day.entries.filterIsInstance<TimelineEntry.Visit>()
                Box(Modifier.fillMaxWidth().weight(0.45f)) {
                    TimelineMap(day, visits, dark, settings.cartoKey, focus)
                }
                LazyColumn(
                    Modifier.fillMaxWidth().weight(0.55f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { SummaryCard(day) }
                    if (day.entries.isEmpty()) {
                        item {
                            Text(
                                if (day.routes.isNotEmpty()) {
                                    "Dawarich hasn't sorted this day into stops and drives yet. The route on the map is from your recorded points."
                                } else {
                                    "Nothing recorded on this day."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(day.entries) { entry ->
                        when (entry) {
                            is TimelineEntry.Visit -> VisitRow(entry, visits.indexOf(entry) + 1) {
                                val lat = entry.lat
                                val lon = entry.lon
                                if (lat != null && lon != null) focus = listOf(lat to lon)
                            }
                            is TimelineEntry.Journey -> JourneyRow(entry) {
                                day.routes.firstOrNull { it.trackId == entry.trackId }?.let { focus = it.points }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= System.currentTimeMillis()
            },
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        vm.setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text("Show") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

@Composable
private fun DateBar(date: LocalDate, onPrevious: () -> Unit, onNext: () -> Unit, onPick: () -> Unit, onRefresh: () -> Unit) {
    val today = LocalDate.now()
    val label = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(
            DateTimeFormatter.ofPattern(if (date.year == today.year) "EEE, MMM d" else "EEE, MMM d, yyyy", Locale.getDefault()),
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day") }
        Text(
            label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f).clickable(onClick = onPick).padding(vertical = 8.dp),
        )
        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
        IconButton(onClick = onNext, enabled = date.isBefore(today)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
        }
    }
}

@Composable
private fun SummaryCard(day: TimelineDay) {
    val s = day.summary
    val parts = buildList {
        if (s != null && (s.distance > 0 || s.places > 0)) {
            add(String.format(Locale.US, "%.1f %s", s.distance, s.distanceUnit))
            add(if (s.places == 1) "1 place" else "${s.places} places")
            if (s.movingMinutes > 0) add("${Format.duration(s.movingMinutes * 60_000L)} moving")
        } else if (day.routes.isNotEmpty()) {
            val meters = day.routes.sumOf { r -> r.points.zipWithNext { a, b -> distanceMeters(a.first, a.second, b.first, b.second) }.sum() }
            add("≈ " + String.format(Locale.US, "%.1f mi", meters / 1609.344) + " recorded")
        }
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Text(
            if (parts.isEmpty()) "No movement recorded" else parts.joinToString(" · "),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun VisitRow(v: TimelineEntry.Visit, number: Int, onClick: () -> Unit) {
    val start = Format.time(v.startedAt.toEpochMilli())
    val end = Format.time(v.endedAt.toEpochMilli())
    val length = Format.duration(v.endedAt.toEpochMilli() - v.startedAt.toEpochMilli())
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF0E7490)),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(v.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val extra = buildList {
                add("$start – $end")
                add(length)
                addAll(v.tags)
                if (v.status == "suggested") add("suggested")
            }
            Text(
                extra.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun JourneyRow(j: TimelineEntry.Journey, onClick: () -> Unit) {
    val mode = j.mode?.lowercase(Locale.US).orEmpty()
    val icon: ImageVector = when {
        "walk" in mode || "foot" in mode -> Icons.Filled.DirectionsWalk
        "cycl" in mode || "bike" in mode -> Icons.Filled.DirectionsBike
        "train" in mode || "bus" in mode || "transit" in mode || "tram" in mode -> Icons.Filled.DirectionsTransit
        "fly" in mode || "plane" in mode || "flight" in mode -> Icons.Filled.Flight
        else -> Icons.Filled.DirectionsCar
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(
            listOf(
                String.format(Locale.US, "%.1f %s", j.distance, j.distanceUnit),
                Format.duration(j.durationSeconds * 1000),
                if (j.avgSpeed > 0) String.format(Locale.US, "avg %.0f %s", j.avgSpeed, j.speedUnit) else null,
            ).filterNotNull().joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Map of the day: routes, numbered stops; zooms to fit, or to whatever you tapped in the list. */
@Composable
private fun TimelineMap(
    day: TimelineDay,
    visits: List<TimelineEntry.Visit>,
    dark: Boolean,
    cartoKey: String,
    focus: List<Pair<Double, Double>>?,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val icons = remember { MarkerIcons(ctx) }
    val map = remember {
        MapView(ctx).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setTilesScaledToDpi(true)
            setMinZoomLevel(3.0)
            setMaxZoomLevel(20.0)
            setDestroyMode(false)
            controller.setZoom(12.0)
        }
    }
    val layer = remember { FolderOverlay().also { map.overlays.add(it) } }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        map.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            map.onDetach()
        }
    }

    LaunchedEffect(dark, cartoKey) { MapTiles.applyTo(map, dark, cartoKey) }

    LaunchedEffect(day) {
        layer.items.clear()
        val routeColor = if (dark) 0xFF7FA2FF.toInt() else 0xFF1F4FD8.toInt()
        val casing = if (dark) 0xFF0B1220.toInt() else android.graphics.Color.WHITE
        day.routes.forEach { route ->
            val pts = route.points.map { GeoPoint(it.first, it.second) }
            layer.add(timelineLine(map, pts, casing, 8f * density))
            layer.add(timelineLine(map, pts, routeColor, 4.5f * density))
        }
        visits.forEachIndexed { i, v ->
            val lat = v.lat ?: return@forEachIndexed
            val lon = v.lon ?: return@forEachIndexed
            val m = Marker(map)
            m.position = GeoPoint(lat, lon)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            m.icon = icons.numbered(i + 1)
            m.title = v.name
            layer.add(m)
        }
        map.invalidate()
        val all = day.routes.flatMap { it.points } + visits.mapNotNull { v -> v.lat?.let { la -> v.lon?.let { lo -> la to lo } } }
        fitTimeline(map, all, density, animate = false)
    }

    LaunchedEffect(focus) {
        val f = focus ?: return@LaunchedEffect
        fitTimeline(map, f, density, animate = true)
    }

    AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
}

private fun timelineLine(map: MapView, pts: List<GeoPoint>, color: Int, width: Float): Polyline =
    Polyline(map).apply {
        setPoints(pts)
        outlinePaint.color = color
        outlinePaint.strokeWidth = width
        outlinePaint.strokeCap = Paint.Cap.ROUND
        outlinePaint.strokeJoin = Paint.Join.ROUND
        outlinePaint.isAntiAlias = true
        setOnClickListener { _, _, _ -> true }
    }

private fun fitTimeline(map: MapView, pts: List<Pair<Double, Double>>, density: Float, animate: Boolean) {
    if (pts.isEmpty()) return
    if (pts.size == 1) {
        map.controller.animateTo(GeoPoint(pts[0].first, pts[0].second), 16.0, 600L)
        return
    }
    var north = pts.maxOf { it.first }
    var south = pts.minOf { it.first }
    var east = pts.maxOf { it.second }
    var west = pts.minOf { it.second }
    val min = 0.004
    if (north - south < min) {
        val c = (north + south) / 2
        north = c + min / 2
        south = c - min / 2
    }
    if (east - west < min) {
        val c = (east + west) / 2
        east = c + min / 2
        west = c - min / 2
    }
    val padLat = (north - south) * 0.12
    val padLon = (east - west) * 0.12
    val box = BoundingBox(north + padLat, east + padLon, south - padLat, west - padLon)
    val border = (12 * density).toInt()
    if (map.width > 0 && map.height > 0) {
        map.zoomToBoundingBox(box, animate, border)
    } else {
        map.addOnFirstLayoutListener { _, _, _, _, _ -> map.zoomToBoundingBox(box, false, border) }
    }
}
