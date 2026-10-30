package com.moblized.parkpal.ui

import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import java.net.URLEncoder

/**
 * Map styles. By default the standard OpenStreetMap map (no key needed). If you add a free CARTO key in
 * Settings, you get CARTO's cleaner Voyager style and a real dark map, in sharp high-resolution tiles.
 */
object MapTiles {
    const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"
    const val CARTO_ATTRIBUTION = "© OpenStreetMap contributors © CARTO"
    const val CARTO_KEY_URL = "https://carto.com/basemaps/apikey/"

    private val cartoSources = HashMap<String, ITileSource>()

    fun source(dark: Boolean, cartoKey: String): ITileSource {
        val key = cartoKey.trim()
        if (key.isEmpty()) return TileSourceFactory.MAPNIK
        return synchronized(cartoSources) {
            cartoSources.getOrPut("$dark|$key") {
                val style = if (dark) "dark_all/" else "rastertiles/voyager/"
                XYTileSource(
                    if (dark) "CartoDarkMatterKeyed" else "CartoVoyagerKeyed",
                    1, 19, 256,
                    "@2x.png?key=" + URLEncoder.encode(key, "UTF-8"),
                    arrayOf("https://basemaps.cartocdn.com/$style"),
                    CARTO_ATTRIBUTION,
                )
            }
        }
    }

    fun attribution(cartoKey: String): String = if (cartoKey.isBlank()) OSM_ATTRIBUTION else CARTO_ATTRIBUTION

    /** The standard map has no dark style, so dark mode inverts its colors instead. */
    fun invertInDarkMode(cartoKey: String): Boolean = cartoKey.isBlank()
}
