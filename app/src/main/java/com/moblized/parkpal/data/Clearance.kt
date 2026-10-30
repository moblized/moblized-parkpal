package com.moblized.parkpal.data

import com.moblized.parkpal.util.Format

enum class FitStatus {
    /** Has a posted height limit your vehicle clears. */
    FITS,
    /** Posted height limit is lower than your vehicle plus margin. */
    TOO_LOW,
    /** Covered parking with no height listed in the map data. */
    CHECK_SIGN,
    /** Mapped as having no height restriction. */
    NO_LIMIT,
    /** Open-air lot or street: no roof. */
    OPEN_AIR,
}

object Clearance {
    private const val INCH = 0.0254

    private val feetInches =
        Regex("""^(\d+(?:\.\d+)?)\s*(?:'|ft|feet)\s*(?:(\d+(?:\.\d+)?)\s*(?:"|''|in|inch|inches)?)?$""")
    private val inchesOnly = Regex("""^(\d+(?:\.\d+)?)\s*(?:"|''|in|inch|inches)$""")
    private val meters = Regex("""^(\d+(?:[.,]\d+)?)\s*(?:m|meter|meters|metre|metres)?$""")

    /** Parses an OpenStreetMap maxheight value ("2.1", "2.1 m", "6'8\"", "7 ft") into meters. */
    fun parseMaxHeight(raw: String?): Double? {
        val s = raw?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null
        feetInches.find(s)?.let { m ->
            val ft = m.groupValues[1].toDouble()
            val inch = m.groupValues[2].toDoubleOrNull() ?: 0.0
            return (ft * 12 + inch) * INCH
        }
        inchesOnly.find(s)?.let { m -> return m.groupValues[1].toDouble() * INCH }
        meters.find(s)?.let { m -> return m.groupValues[1].replace(',', '.').toDoubleOrNull() }
        return null
    }

    fun fitStatus(place: ParkingPlace, settings: AppSettings): FitStatus {
        if (place.maxHeightRaw?.trim()?.lowercase() == "none") return FitStatus.NO_LIMIT
        val limit = place.maxHeightMeters
        if (limit != null) {
            val needed = (settings.vehicleHeightIn + settings.marginIn) * INCH
            return if (limit >= needed) FitStatus.FITS else FitStatus.TOO_LOW
        }
        return if (place.kind.covered) FitStatus.CHECK_SIGN else FitStatus.OPEN_AIR
    }

    fun describe(place: ParkingPlace, status: FitStatus, s: AppSettings): String {
        val limit = place.maxHeightMeters?.let { Format.metersToFtIn(it) } ?: "?"
        return when (status) {
            FitStatus.FITS -> "Height limit $limit · your ${s.vehicleName} fits"
            FitStatus.TOO_LOW -> "Height limit $limit · too low (you need ${Format.ftIn(s.vehicleHeightIn + s.marginIn)})"
            FitStatus.CHECK_SIGN -> "Covered parking, height not listed · check the sign before you enter"
            FitStatus.NO_LIMIT -> "No height limit"
            FitStatus.OPEN_AIR -> "Open-air · no height limit"
        }
    }

    fun shortLabel(status: FitStatus): String = when (status) {
        FitStatus.FITS -> "Fits"
        FitStatus.TOO_LOW -> "Too low"
        FitStatus.CHECK_SIGN -> "Check height"
        FitStatus.NO_LIMIT -> "No height limit"
        FitStatus.OPEN_AIR -> "No roof"
    }
}
