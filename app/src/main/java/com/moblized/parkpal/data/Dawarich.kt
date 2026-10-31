package com.moblized.parkpal.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Talks to your self-hosted Dawarich (location history) server with your Dawarich API key. */
object Dawarich {
    /** Marks places ParkPal created, so they aren't offered back as destinations. */
    const val PARKPAL_NOTE = "Saved from ParkPal"

    class HttpError(val code: Int, message: String) : IOException(message)

    data class Place(
        val id: Long,
        val name: String,
        val lat: Double,
        val lon: Double,
        val note: String,
        val tags: List<String>,
        val visits: Int,
    )

    fun configured(s: AppSettings): Boolean = s.dawarichUrl.isNotBlank() && s.dawarichKey.isNotBlank()

    fun normalize(url: String): String = ParkingServer.normalize(url)

    /** The account's email, to confirm the address and key work. */
    suspend fun me(url: String, key: String): String = withContext(Dispatchers.IO) {
        val o = JSONObject(request("GET", "${normalize(url)}/api/v1/users/me", key, null))
        o.optJSONObject("user")?.optString("email").orEmpty()
    }

    suspend fun places(url: String, key: String): List<Place> = withContext(Dispatchers.IO) {
        val arr = JSONArray(request("GET", "${normalize(url)}/api/v1/places", key, null))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optDouble("latitude")
            val lon = o.optDouble("longitude")
            val name = o.optString("name").trim()
            if (lat.isNaN() || lon.isNaN() || name.isEmpty() || o.optBoolean("archived")) return@mapNotNull null
            val tagArr = o.optJSONArray("tags") ?: JSONArray()
            Place(
                id = o.optLong("id"),
                name = name,
                lat = lat,
                lon = lon,
                note = if (o.isNull("note")) "" else o.optString("note"),
                tags = (0 until tagArr.length()).mapNotNull { j ->
                    tagArr.optJSONObject(j)?.let { t ->
                        listOf(t.optString("icon").takeIf { !t.isNull("icon") && it.isNotBlank() }, t.optString("name"))
                            .filterNotNull().joinToString(" ").trim().ifEmpty { null }
                    }
                },
                visits = o.optInt("visits_count"),
            )
        }
    }

    /** Adds a finished parking stop to your Dawarich timeline. Returns the visit id. */
    suspend fun createVisit(
        url: String,
        key: String,
        name: String,
        lat: Double,
        lon: Double,
        startedAt: Long,
        endedAt: Long,
    ): Long = withContext(Dispatchers.IO) {
        val visit = JSONObject()
            .put("name", name)
            .put("latitude", lat)
            .put("longitude", lon)
            .put("started_at", Instant.ofEpochMilli(startedAt).toString())
            .put("ended_at", Instant.ofEpochMilli(endedAt).toString())
            .put("status", "confirmed")
        val o = JSONObject(request("POST", "${normalize(url)}/api/v1/visits", key, JSONObject().put("visit", visit).toString()))
        o.optLong("id")
    }

    /** Saves a parking spot as a Dawarich place. Returns the place id. */
    suspend fun createPlace(url: String, key: String, name: String, lat: Double, lon: Double, note: String): Long =
        withContext(Dispatchers.IO) {
            val place = JSONObject()
                .put("name", name)
                .put("latitude", lat)
                .put("longitude", lon)
                .put("source", "manual")
                .put("note", note)
            val o = JSONObject(request("POST", "${normalize(url)}/api/v1/places", key, JSONObject().put("place", place).toString()))
            o.optLong("id")
        }

    /** Uploads recorded points (GeoJSON features, as JSON strings) in one batch. */
    suspend fun uploadPoints(url: String, key: String, features: List<String>) {
        withContext(Dispatchers.IO) {
            val body = features.joinToString(separator = ",", prefix = "{\"locations\":[", postfix = "]}")
            request("POST", "${normalize(url)}/api/v1/points", key, body)
        }
    }

    private fun request(method: String, url: String, key: String, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer ${key.trim()}")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", OverpassApi.USER_AGENT)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            if (code in 200..299) return conn.inputStream.bufferedReader().use { it.readText() }
            val detail = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            throw HttpError(
                code,
                when (code) {
                    401, 403 -> "Dawarich didn't accept the API key"
                    404 -> "Dawarich address not found (check the URL)"
                    else -> "Dawarich error (HTTP $code) ${detail.take(160)}".trim()
                },
            )
        } finally {
            conn.disconnect()
        }
    }
}
