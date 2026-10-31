package com.moblized.parkpal.ui

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moblized.parkpal.data.ParkActions
import com.moblized.parkpal.data.SettingsStore
import com.moblized.parkpal.nav.Directions
import com.moblized.parkpal.nav.ManeuverKind
import com.moblized.parkpal.nav.NavRequest
import com.moblized.parkpal.nav.NavRoute
import com.moblized.parkpal.nav.NavSession
import com.moblized.parkpal.nav.NavState
import com.moblized.parkpal.nav.NavStatus
import com.moblized.parkpal.nav.NavText
import com.moblized.parkpal.nav.NavigationService
import com.moblized.parkpal.nav.Progress
import com.moblized.parkpal.nav.TravelMode
import com.moblized.parkpal.util.Nav
import kotlinx.coroutines.launch
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.DateFormat
import java.util.Date

private val NavGreen = Color(0xFF0B6E4F)
private val NavGreenDark = Color(0xFF08513A)

/** Full-screen turn-by-turn view, shown over the app while ParkPal is navigating. */
@Composable
fun NavigationScreen(state: NavState, dark: Boolean, onParked: () -> Unit) {
    val ctx = LocalContext.current
    val settings by SettingsStore.settings.collectAsStateWithLifecycle()
    val view = LocalView.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var following by remember { mutableStateOf(true) }
    var overviewTick by remember { mutableStateOf(0) }

    // Keep the screen on while navigating, like Google Maps.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    BackHandler {
        // Back leaves the trip running in the background (like Google Maps); End stops it.
        if (state.status == NavStatus.ARRIVED || state.status == NavStatus.FAILED) {
            NavSession.dismiss()
        } else {
            (ctx as? android.app.Activity)?.moveTaskToBack(true)
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavMap(
            state = state,
            dark = dark,
            cartoKey = settings.cartoKey.trim(),
            following = following,
            overviewTick = overviewTick,
            onUserMoved = { following = false },
        )

        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DirectionBanner(state)
        }

        // Right-side buttons: voice on/off and route overview / recenter.
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallFloatingActionButton(
                onClick = { SettingsStore.update { it.copy(navVoice = !it.navVoice) } },
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Icon(
                    if (settings.navVoice) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = if (settings.navVoice) "Mute directions" else "Speak directions",
                )
            }
            if (state.route != null && state.status != NavStatus.ARRIVED) {
                SmallFloatingActionButton(
                    onClick = {
                        if (following) {
                            following = false
                            overviewTick++
                        } else {
                            following = true
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Icon(
                        if (following) Icons.Filled.ZoomOutMap else Icons.Filled.MyLocation,
                        contentDescription = if (following) "Show whole route" else "Follow my position",
                    )
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AnimatedVisibility(visible = !following && state.status == NavStatus.NAVIGATING, enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    FilledTonalButton(onClick = { following = true }) {
                        Icon(Icons.Filled.MyLocation, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Re-center")
                    }
                }
            }
            when (state.status) {
                NavStatus.ARRIVED -> ArrivalCard(
                    state.request,
                    onParkHere = {
                        val req = state.request
                        val place = req.parking
                        if (place != null) {
                            ParkActions.parkAt(ctx, place.lat, place.lon, place.name, req.after)
                            NavSession.dismiss()
                            onParked()
                        } else {
                            scope.launch {
                                ParkActions.parkHere(ctx, req.after ?: req.dest)
                                NavSession.dismiss()
                                onParked()
                            }
                        }
                    },
                    onParkAndWalk = {
                        val req = state.request
                        val place = req.parking
                        val after = req.after
                        if (place != null && after != null) {
                            ParkActions.parkAt(ctx, place.lat, place.lon, place.name, after)
                            NavSession.dismiss()
                            Directions.walkTo(ctx, after)
                        }
                    },
                    onEndParking = {
                        ParkActions.endParking(ctx)
                        NavSession.dismiss()
                    },
                    onDone = { NavSession.dismiss() },
                )
                NavStatus.FAILED -> FailedCard(
                    state,
                    onRetry = { NavigationService.start(ctx, state.request) },
                    onGoogle = {
                        val d = state.request.dest
                        NavSession.dismiss()
                        Nav.navigate(ctx, d.lat, d.lon, walking = state.request.mode == TravelMode.WALK)
                    },
                    onClose = { NavSession.dismiss() },
                )
                else -> TripBar(state, onEnd = { NavigationService.stop(ctx) })
            }
        }
    }
}

@Composable
private fun DirectionBanner(state: NavState) {
    val route = state.route
    val p = state.progress
    val step = if (route != null && p != null) route.steps.getOrNull(p.nextStep) else null
    when {
        state.status == NavStatus.ROUTING || (route == null && state.status != NavStatus.FAILED) -> BannerSurface {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 3.dp)
                Spacer(Modifier.width(14.dp))
                Text("Getting directions to ${state.request.dest.title}…", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
        state.status == NavStatus.ARRIVED -> Unit
        state.status == NavStatus.FAILED -> Unit
        route != null && step == null -> BannerSurface {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ManeuverIcon(route.steps.firstOrNull()?.kind ?: ManeuverKind.DEPART, Color.White)
                Spacer(Modifier.width(14.dp))
                Text(route.steps.firstOrNull()?.text ?: "Starting…", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }
        route != null && step != null && p != null -> {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                BannerSurface {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ManeuverIcon(step.kind, Color.White, 54.dp)
                            Text(
                                NavText.short(p.toNextStep),
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(
                            step.text,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 26.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                // "Then ↱" when the next turn comes right after this one.
                val then = route.steps.getOrNull(p.nextStep + 1)
                val gap = then?.let { it.startDist - step.startDist } ?: Double.MAX_VALUE
                val soon = if (route.mode == TravelMode.WALK) 50.0 else 300.0
                if (then != null && gap < soon && then.kind != ManeuverKind.ARRIVE) {
                    Surface(
                        color = NavGreenDark,
                        shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
                        modifier = Modifier.padding(start = 12.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Then", color = Color.White, style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.width(6.dp))
                            ManeuverIcon(then.kind, Color.White, 22.dp)
                        }
                    }
                }
                if (state.status == NavStatus.REROUTING) {
                    Spacer(Modifier.size(6.dp))
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(50)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Finding a new route…", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BannerSurface(content: @Composable () -> Unit) {
    Surface(color = NavGreen, shape = RoundedCornerShape(16.dp), shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
        content()
    }
}

@Composable
private fun TripBar(state: NavState, onEnd: () -> Unit) {
    val p = state.progress
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.request.mode == TravelMode.DRIVE) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(NavText.speed(state.speed), fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 18.sp)
                        Text("mph", fontSize = 10.sp, lineHeight = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(12.dp))
            } else {
                Icon(Icons.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.padding(end = 12.dp))
            }
            Column(Modifier.weight(1f)) {
                if (p != null) {
                    Text(
                        NavText.duration(p.remainingSeconds),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (MaterialTheme.colorScheme.background.lum() < 0.5f) Color(0xFF5DD39E) else NavGreen,
                    )
                    val eta = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + (p.remainingSeconds * 1000).toLong()))
                    Text(
                        "${NavText.short(p.remainingMeters)} · $eta",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(state.request.dest.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Surface(
                onClick = onEnd,
                shape = CircleShape,
                color = Color(0xFFD93025),
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = "End navigation", tint = Color.White)
                }
            }
        }
    }
}

private fun Color.lum(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
private fun ArrivalCard(
    req: NavRequest,
    onParkHere: () -> Unit,
    onParkAndWalk: () -> Unit,
    onEndParking: () -> Unit,
    onDone: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ManeuverIcon(ManeuverKind.ARRIVE, MaterialTheme.colorScheme.primary, 36.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("You've arrived", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        req.dest.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val after = req.after
            when {
                req.parking != null && after != null -> {
                    Button(onClick = onParkAndWalk, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.DirectionsWalk, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Park here & walk to ${after.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = onParkHere, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.LocalParking, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Just save my spot")
                    }
                }
                req.offerParking -> Button(onClick = onParkHere, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.LocalParking, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Save my parking spot")
                }
                req.toCar -> Button(onClick = onEndParking, modifier = Modifier.fillMaxWidth()) {
                    Text("I'm at my truck · end parking")
                }
            }
            TextButton(onClick = onDone, modifier = Modifier.align(Alignment.End)) { Text("Done") }
        }
    }
}

@Composable
private fun FailedCard(state: NavState, onRetry: () -> Unit, onGoogle: () -> Unit, onClose: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Couldn't start directions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(state.error ?: "Something went wrong.", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Try again")
                }
                OutlinedButton(onClick = onGoogle) {
                    Icon(Icons.Filled.Map, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Google Maps")
                }
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}

/** The map under the banner: route ahead in blue, driven part in grey, your arrow, following you heading-up. */
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun NavMap(
    state: NavState,
    dark: Boolean,
    cartoKey: String,
    following: Boolean,
    overviewTick: Int,
    onUserMoved: () -> Unit,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val icons = remember { MarkerIcons(ctx) }
    val map = remember {
        MapView(ctx).apply {
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            setDestroyMode(false)
            setTilesScaledToDpi(true)
            setMinZoomLevel(3.0)
            setMaxZoomLevel(20.0)
            // Until the route arrives, show the destination rather than the middle of the ocean.
            controller.setZoom(15.0)
            controller.setCenter(GeoPoint(state.request.dest.lat, state.request.dest.lon))
        }
    }
    val holder = remember { NavOverlays(map, icons, density) }
    val latestOnUserMoved = androidx.compose.runtime.rememberUpdatedState(onUserMoved)
    remember {
        map.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_MOVE) latestOnUserMoved.value()
            false
        }
        0
    }

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

    val route = state.route
    LaunchedEffect(route) {
        holder.setRoute(route, state.request.dest.lat, state.request.dest.lon, dark)
        if (route != null && state.progress == null) holder.overview(route, null)
    }

    LaunchedEffect(overviewTick) {
        val r = state.route ?: return@LaunchedEffect
        if (overviewTick > 0) holder.overview(r, state.progress)
    }

    val p = state.progress
    LaunchedEffect(p, following) {
        val r = state.route ?: return@LaunchedEffect
        if (p == null) return@LaunchedEffect
        holder.setProgress(r, p)
        if (following) {
            val walk = r.mode == TravelMode.WALK
            val zoom = when {
                walk -> 18.0
                state.speed < 8f -> 17.5
                state.speed < 20f -> 16.5
                else -> 15.5
            }
            map.setMapCenterOffset(0, (map.height * 0.22).toInt())
            map.controller.animateTo(GeoPoint(p.lat, p.lon), zoom, 900L, -p.bearing)
        }
    }

    AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
}

/** The route, destination and position overlays, updated in place each second. */
private class NavOverlays(private val map: MapView, private val icons: MarkerIcons, private val density: Float) {
    private val routeLayer = FolderOverlay()
    private val doneLine = line(0xFF9AA0A6.toInt(), 8f)
    private val aheadCasing = line(0xFF0B3D91.toInt(), 11f)
    private val aheadLine = line(0xFF1A73E8.toInt(), 7f)
    private val destMarker = Marker(map).apply {
        icon = icons.destination()
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        setOnMarkerClickListener { _, _ -> true }
    }
    private val me = Marker(map).apply {
        icon = icons.navArrow()
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        isFlat = true
        setOnMarkerClickListener { _, _ -> true }
    }
    private var shown: NavRoute? = null

    init {
        routeLayer.add(doneLine)
        routeLayer.add(aheadCasing)
        routeLayer.add(aheadLine)
        map.overlays.add(routeLayer)
        map.overlays.add(destMarker)
        map.overlays.add(me)
        me.isEnabled = false
    }

    private fun line(color: Int, width: Float) = Polyline(map).apply {
        outlinePaint.color = color
        outlinePaint.strokeWidth = width * density
        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
        outlinePaint.isAntiAlias = true
        setOnClickListener { _, _, _ -> true }
    }

    fun setRoute(route: NavRoute?, destLat: Double, destLon: Double, dark: Boolean) {
        destMarker.position = GeoPoint(destLat, destLon)
        aheadCasing.outlinePaint.color = if (dark) 0xFF0B1220.toInt() else 0xFF0B3D91.toInt()
        shown = route
        if (route == null) {
            doneLine.setPoints(emptyList())
            aheadLine.setPoints(emptyList())
            aheadCasing.setPoints(emptyList())
        } else {
            val all = (0 until route.size).map { GeoPoint(route.lats[it], route.lons[it]) }
            doneLine.setPoints(emptyList())
            aheadCasing.setPoints(all)
            aheadLine.setPoints(all)
        }
        map.invalidate()
    }

    fun setProgress(route: NavRoute, p: Progress) {
        if (route !== shown) return
        val here = GeoPoint(p.lat, p.lon)
        val split = (p.pointIndex + 1).coerceIn(0, route.size)
        val done = ArrayList<GeoPoint>(split + 1)
        for (i in 0 until split) done += GeoPoint(route.lats[i], route.lons[i])
        done += here
        val ahead = ArrayList<GeoPoint>(route.size - split + 1)
        ahead += here
        for (i in split until route.size) ahead += GeoPoint(route.lats[i], route.lons[i])
        doneLine.setPoints(done)
        aheadCasing.setPoints(ahead)
        aheadLine.setPoints(ahead)
        me.position = here
        me.rotation = -p.bearing
        me.isEnabled = true
        map.invalidate()
    }

    /** Shows the rest of the route, north up. */
    fun overview(route: NavRoute, p: Progress?) {
        val from = p?.pointIndex ?: 0
        var n = -90.0
        var s = 90.0
        var e = -180.0
        var w = 180.0
        for (i in from until route.size) {
            n = maxOf(n, route.lats[i]); s = minOf(s, route.lats[i])
            e = maxOf(e, route.lons[i]); w = minOf(w, route.lons[i])
        }
        if (n < s) return
        val padLat = maxOf((n - s) * 0.25, 0.002)
        val padLon = maxOf((e - w) * 0.15, 0.002)
        map.setMapCenterOffset(0, 0)
        map.mapOrientation = 0f
        val box = BoundingBox(n + padLat * 1.4, e + padLon, s - padLat * 1.6, w - padLon)
        if (map.width > 0 && map.height > 0) {
            map.zoomToBoundingBox(box, true, (24 * density).toInt())
        } else {
            map.addOnFirstLayoutListener { _, _, _, _, _ -> map.zoomToBoundingBox(box, false, (24 * density).toInt()) }
        }
    }
}
