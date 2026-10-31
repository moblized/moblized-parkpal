package com.moblized.parkpal.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How closely to record your location for Dawarich. */
enum class RecordMode(val label: String, val detail: String) {
    BATTERY("Battery saver", "About every 5 minutes, using little battery"),
    BALANCED("Balanced", "Light while you're still, detailed while you're driving"),
    PRECISE("Precise", "Every few seconds with GPS; uses the most battery"),
}

enum class ThemeMode(val label: String) {
    SYSTEM("Match system"),
    LIGHT("Light"),
    DARK("Dark"),
    BLACK("Pure black"),
}

data class AppSettings(
    val vehicleName: String = "truck",
    /** Total vehicle height in inches. */
    val vehicleHeightIn: Int = 81,
    /** Extra clearance to keep above the vehicle, in inches. */
    val marginIn: Int = 2,
    val radiusMeters: Int = 1600,
    val reminderLeadMin: Int = 10,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val payApp: String = "ParkMobile",
    /** Look for parking as soon as you pick a destination. */
    val autoFindParking: Boolean = true,
    /** How far from the destination to look for parking. */
    val walkRadiusMeters: Int = 800,
    /** Optional free CARTO key for the nicer map style. Blank uses the standard OpenStreetMap map. */
    val cartoKey: String = "",
    /** Your own ParkPal parking server (e.g. on TrueNAS). Blank uses the public servers. */
    val serverUrl: String = "",
    val serverToken: String = "",
    /** Your Dawarich (location history) server and API key. */
    val dawarichUrl: String = "",
    val dawarichKey: String = "",
    /** Add finished parking stops to your Dawarich timeline as visits. */
    val dawarichVisits: Boolean = true,
    /** Offer your Dawarich places as destinations in Where to? and Android Auto. */
    val dawarichPlaces: Boolean = true,
    /** Save starred garages as Dawarich places. */
    val dawarichFavorites: Boolean = true,
    /** Record your location in the background and send it to Dawarich. */
    val recordLocation: Boolean = false,
    val recordMode: RecordMode = RecordMode.BALANCED,
)

object SettingsStore {
    private lateinit var prefs: SharedPreferences
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("parkpal_settings", Context.MODE_PRIVATE)
        val d = AppSettings()
        _settings.value = AppSettings(
            vehicleName = prefs.getString("vehicleName", null) ?: d.vehicleName,
            vehicleHeightIn = prefs.getInt("vehicleHeightIn", d.vehicleHeightIn),
            marginIn = prefs.getInt("marginIn", d.marginIn),
            radiusMeters = prefs.getInt("radiusMeters", d.radiusMeters),
            reminderLeadMin = prefs.getInt("reminderLeadMin", d.reminderLeadMin),
            themeMode = enumOr(prefs.getString("themeMode", null), d.themeMode),
            payApp = prefs.getString("payApp", null) ?: d.payApp,
            autoFindParking = prefs.getBoolean("autoFindParking", d.autoFindParking),
            walkRadiusMeters = prefs.getInt("walkRadiusMeters", d.walkRadiusMeters),
            cartoKey = prefs.getString("cartoKey", null) ?: d.cartoKey,
            serverUrl = prefs.getString("serverUrl", null) ?: d.serverUrl,
            serverToken = prefs.getString("serverToken", null) ?: d.serverToken,
            dawarichUrl = prefs.getString("dawarichUrl", null) ?: d.dawarichUrl,
            dawarichKey = prefs.getString("dawarichKey", null) ?: d.dawarichKey,
            dawarichVisits = prefs.getBoolean("dawarichVisits", d.dawarichVisits),
            dawarichPlaces = prefs.getBoolean("dawarichPlaces", d.dawarichPlaces),
            dawarichFavorites = prefs.getBoolean("dawarichFavorites", d.dawarichFavorites),
            recordLocation = prefs.getBoolean("recordLocation", d.recordLocation),
            recordMode = enumOr(prefs.getString("recordMode", null), d.recordMode),
        )
    }

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val s = transform(_settings.value)
        _settings.value = s
        if (!::prefs.isInitialized) return
        prefs.edit()
            .putString("vehicleName", s.vehicleName)
            .putInt("vehicleHeightIn", s.vehicleHeightIn)
            .putInt("marginIn", s.marginIn)
            .putInt("radiusMeters", s.radiusMeters)
            .putInt("reminderLeadMin", s.reminderLeadMin)
            .putString("themeMode", s.themeMode.name)
            .putString("payApp", s.payApp)
            .putBoolean("autoFindParking", s.autoFindParking)
            .putInt("walkRadiusMeters", s.walkRadiusMeters)
            .putString("cartoKey", s.cartoKey)
            .putString("serverUrl", s.serverUrl)
            .putString("serverToken", s.serverToken)
            .putString("dawarichUrl", s.dawarichUrl)
            .putString("dawarichKey", s.dawarichKey)
            .putBoolean("dawarichVisits", s.dawarichVisits)
            .putBoolean("dawarichPlaces", s.dawarichPlaces)
            .putBoolean("dawarichFavorites", s.dawarichFavorites)
            .putBoolean("recordLocation", s.recordLocation)
            .putString("recordMode", s.recordMode.name)
            .apply()
    }
}
