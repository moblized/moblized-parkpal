package com.moblized.parkpal.data

import org.json.JSONObject

enum class ParkingKind(val label: String, val covered: Boolean) {
    SURFACE("Lot", false),
    GARAGE("Garage", true),
    UNDERGROUND("Underground", true),
    STREET("Street", false),
    /** Type not known (e.g. some Google places): could be a lot or a garage. */
    UNKNOWN("Parking", false),
}

enum class FeeInfo(val label: String) {
    FREE("Free"),
    PAID("Paid"),
    UNKNOWN("Fee not listed"),
}

/** A parking lot, garage or street-parking area from OpenStreetMap. */
data class ParkingPlace(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val kind: ParkingKind,
    val fee: FeeInfo,
    val maxHeightMeters: Double? = null,
    val maxHeightRaw: String? = null,
    val capacity: String? = null,
    val openingHours: String? = null,
    val operator: String? = null,
    val access: String? = null,
    /** "google" for places that came from Google via your server; null for OpenStreetMap. */
    val source: String? = null,
    val address: String? = null,
) {
    val fromGoogle: Boolean get() = source == "google"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("lat", lat)
        put("lon", lon)
        put("kind", kind.name)
        put("fee", fee.name)
        putOpt("mh", maxHeightMeters)
        putOpt("mhRaw", maxHeightRaw)
        putOpt("cap", capacity)
        putOpt("hours", openingHours)
        putOpt("op", operator)
        putOpt("access", access)
        putOpt("src", source)
        putOpt("addr", address)
    }

    companion object {
        fun fromJson(o: JSONObject) = ParkingPlace(
            id = o.getString("id"),
            name = o.getString("name"),
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
            kind = enumOr(o.optString("kind"), ParkingKind.SURFACE),
            fee = enumOr(o.optString("fee"), FeeInfo.UNKNOWN),
            maxHeightMeters = o.dbl("mh"),
            maxHeightRaw = o.str("mhRaw"),
            capacity = o.str("cap"),
            openingHours = o.str("hours"),
            operator = o.str("op"),
            access = o.str("access"),
            source = o.str("src"),
            address = o.str("addr"),
        )
    }
}

/** A place you're going to (from search, recents or a dropped pin). */
data class Destination(
    val title: String,
    val subtitle: String,
    val lat: Double,
    val lon: Double,
    /** "dawarich" for your Dawarich places; null for search results and dropped pins. */
    val source: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("subtitle", subtitle)
        put("lat", lat)
        put("lon", lon)
        putOpt("source", source)
    }

    companion object {
        fun fromJson(o: JSONObject) = Destination(
            title = o.getString("title"),
            subtitle = o.optString("subtitle", ""),
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
            source = o.str("source"),
        )
    }
}

/** Where the car is parked (active) or was parked (history). */
data class ParkingSession(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val address: String? = null,
    val placeName: String? = null,
    val note: String = "",
    val level: String = "",
    val photoPath: String? = null,
    val startedAt: Long,
    val meterEndsAt: Long? = null,
    val endedAt: Long? = null,
    /** Where you were headed when you parked, so you can walk there. */
    val destName: String? = null,
    val destLat: Double? = null,
    val destLon: Double? = null,
) {
    fun title(): String = placeName ?: address ?: "Saved spot"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("lat", lat)
        put("lon", lon)
        putOpt("address", address)
        putOpt("placeName", placeName)
        put("note", note)
        put("level", level)
        putOpt("photo", photoPath)
        put("startedAt", startedAt)
        putOpt("meterEndsAt", meterEndsAt)
        putOpt("endedAt", endedAt)
        putOpt("destName", destName)
        putOpt("destLat", destLat)
        putOpt("destLon", destLon)
    }

    companion object {
        fun fromJson(o: JSONObject) = ParkingSession(
            id = o.getLong("id"),
            lat = o.getDouble("lat"),
            lon = o.getDouble("lon"),
            address = o.str("address"),
            placeName = o.str("placeName"),
            note = o.optString("note", ""),
            level = o.optString("level", ""),
            photoPath = o.str("photo"),
            startedAt = o.getLong("startedAt"),
            meterEndsAt = o.lng("meterEndsAt"),
            endedAt = o.lng("endedAt"),
            destName = o.str("destName"),
            destLat = o.dbl("destLat"),
            destLon = o.dbl("destLon"),
        )
    }
}

internal fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

internal fun JSONObject.dbl(key: String): Double? =
    if (has(key) && !isNull(key)) getDouble(key) else null

internal fun JSONObject.lng(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

internal inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
