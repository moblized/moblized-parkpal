package com.moblized.parkpal

import android.app.Application
import com.moblized.parkpal.data.OverpassApi
import com.moblized.parkpal.data.ParkStore
import com.moblized.parkpal.data.SettingsStore
import com.moblized.parkpal.timer.Notifications
import org.osmdroid.config.Configuration
import java.io.File

class ParkPalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        ParkStore.init(this)
        Notifications.createChannels(this)

        val osm = Configuration.getInstance()
        osm.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        osm.userAgentValue = OverpassApi.USER_AGENT
        osm.osmdroidBasePath = File(filesDir, "osmdroid")
        osm.osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
    }
}
