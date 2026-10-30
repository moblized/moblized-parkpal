package com.moblized.parkpal.ui

import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * Map styles from CARTO's CDN (OpenStreetMap data): a clean light style like Google Maps, and a real
 * dark style for dark mode. High-resolution (@2x) tiles keep streets and labels sharp.
 */
object MapTiles {
    const val ATTRIBUTION = "© OpenStreetMap contributors © CARTO"

    private val servers = listOf("a", "b", "c", "d")

    val light = XYTileSource(
        "CartoVoyager", 1, 19, 256, "@2x.png",
        servers.map { "https://$it.basemaps.cartocdn.com/rastertiles/voyager/" }.toTypedArray(),
        ATTRIBUTION,
    )

    val dark = XYTileSource(
        "CartoDarkMatter", 1, 19, 256, "@2x.png",
        servers.map { "https://$it.basemaps.cartocdn.com/dark_all/" }.toTypedArray(),
        ATTRIBUTION,
    )
}
