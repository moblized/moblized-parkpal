package com.moblized.parkpal.ui

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PathEffect
import com.moblized.parkpal.data.Destination
import com.moblized.parkpal.data.FitStatus
import com.moblized.parkpal.data.ParkingPlace
import com.moblized.parkpal.data.ParkingSession
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * Everything ParkPal draws on the map, split into layers so a change only redraws its own layer.
 * Selecting a spot, for example, no longer rebuilds hundreds of parking markers.
 */
class MapLayers(
    private val map: MapView,
    private val icons: MarkerIcons,
    private val density: Float,
    private val onPlace: (ParkingPlace) -> Unit,
    private val onParked: () -> Unit,
    private val onDestination: () -> Unit,
) {
    private val routeLayer = FolderOverlay()
    private val placeLayer = FolderOverlay()
    private val topLayer = FolderOverlay()

    private var places: List<Pair<ParkingPlace, FitStatus>>? = null
    private var selected: Pair<ParkingPlace, FitStatus>? = null
    private var route: RouteUi? = null
    private var destination: Destination? = null
    private var parkedAt: Pair<Double, Double>? = null
    private var dark: Boolean? = null

    init {
        map.overlays.add(routeLayer)
        map.overlays.add(placeLayer)
        map.overlays.add(topLayer)
    }

    fun sync(
        places: List<Pair<ParkingPlace, FitStatus>>,
        selected: Pair<ParkingPlace, FitStatus>?,
        route: RouteUi?,
        destination: Destination?,
        parked: ParkingSession?,
        dark: Boolean,
    ) {
        val parkedAt = parked?.let { it.lat to it.lon }
        val placesChanged = places !== this.places
        val selectedChanged = selected != this.selected
        val destChanged = destination != this.destination
        val routeChanged = route !== this.route || selectedChanged || destChanged || dark != this.dark
        val topChanged = selectedChanged || destChanged || parkedAt != this.parkedAt

        if (placesChanged) drawPlaces(places)
        if (routeChanged) drawRoute(selected?.first, route, destination, dark)
        if (topChanged) drawTop(selected, destination, parkedAt)

        this.places = places
        this.selected = selected
        this.route = route
        this.destination = destination
        this.parkedAt = parkedAt
        this.dark = dark
        if (placesChanged || routeChanged || topChanged) map.invalidate()
    }

    private fun drawPlaces(list: List<Pair<ParkingPlace, FitStatus>>) {
        placeLayer.items.clear()
        // Closest first; a couple hundred markers is plenty and keeps panning smooth.
        list.take(MAX_MARKERS).forEach { (place, status) ->
            val marker = Marker(map)
            marker.position = GeoPoint(place.lat, place.lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.icon = icons.forPlace(place, status, selected = false)
            marker.setOnMarkerClickListener { _, _ ->
                onPlace(place)
                true
            }
            placeLayer.add(marker)
        }
    }

    private fun drawRoute(sel: ParkingPlace?, r: RouteUi?, dest: Destination?, dark: Boolean) {
        routeLayer.items.clear()
        if (sel != null && r != null && r.placeId == sel.id && r.points.size > 1) {
            val pts = r.points.map { GeoPoint(it.first, it.second) }
            val casing = if (dark) 0xFF0B1220.toInt() else android.graphics.Color.WHITE
            val color = if (dark) 0xFF7FA2FF.toInt() else 0xFF1F4FD8.toInt()
            routeLayer.add(line(pts, casing, 10f * density, null))
            routeLayer.add(line(pts, color, 6f * density, null))
        }
        if (sel != null && dest != null) {
            routeLayer.add(
                line(
                    listOf(GeoPoint(sel.lat, sel.lon), GeoPoint(dest.lat, dest.lon)),
                    if (dark) 0xFF9CA3AF.toInt() else 0xFF6B7280.toInt(),
                    4f * density,
                    DashPathEffect(floatArrayOf(1f * density, 8f * density), 0f),
                ),
            )
        }
    }

    private fun drawTop(sel: Pair<ParkingPlace, FitStatus>?, dest: Destination?, parkedAt: Pair<Double, Double>?) {
        topLayer.items.clear()
        if (parkedAt != null) {
            val marker = Marker(map)
            marker.position = GeoPoint(parkedAt.first, parkedAt.second)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.icon = icons.parked()
            marker.setOnMarkerClickListener { _, _ ->
                onParked()
                true
            }
            topLayer.add(marker)
        }
        if (dest != null) {
            val marker = Marker(map)
            marker.position = GeoPoint(dest.lat, dest.lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.icon = icons.destination()
            marker.setOnMarkerClickListener { _, _ ->
                onDestination()
                true
            }
            topLayer.add(marker)
        }
        if (sel != null) {
            val (place, status) = sel
            val marker = Marker(map)
            marker.position = GeoPoint(place.lat, place.lon)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.icon = icons.forPlace(place, status, selected = true)
            marker.setOnMarkerClickListener { _, _ -> true }
            topLayer.add(marker)
        }
    }

    private fun line(pts: List<GeoPoint>, color: Int, width: Float, effect: PathEffect?): Polyline =
        Polyline(map).apply {
            setPoints(pts)
            outlinePaint.color = color
            outlinePaint.strokeWidth = width
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.isAntiAlias = true
            outlinePaint.pathEffect = effect
            setOnClickListener { _, _, _ -> true }
        }

    private companion object {
        const val MAX_MARKERS = 200
    }
}
