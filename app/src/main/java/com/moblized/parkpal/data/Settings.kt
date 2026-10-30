package com.moblized.parkpal.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
            .apply()
    }
}
