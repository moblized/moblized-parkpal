package com.moblized.parkpal

import android.app.Application
import com.moblized.parkpal.data.DawarichSync
import com.moblized.parkpal.data.OverpassApi
import com.moblized.parkpal.data.ParkStore
import com.moblized.parkpal.data.ParkingRepo
import com.moblized.parkpal.data.SettingsStore
import com.moblized.parkpal.record.PointBuffer
import com.moblized.parkpal.timer.Notifications
import org.osmdroid.config.Configuration
import java.io.File

class ParkPalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        ParkStore.init(this)
        ParkingRepo.init(this)
        DawarichSync.init(this)
        PointBuffer.init(this)
        // Send anything waiting for Dawarich and refresh your places.
        DawarichSync.kick(this)
        Notifications.createChannels(this)

        val osm = Configuration.getInstance()
        osm.load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))
        osm.userAgentValue = OverpassApi.USER_AGENT
        osm.osmdroidBasePath = File(filesDir, "osmdroid")
        osm.osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        // Map tiles come from a CDN, so fetch several at once and keep them for two weeks:
        // places you've seen before draw instantly, even offline.
        osm.tileDownloadThreads = 8.toShort()
        osm.tileDownloadMaxQueueSize = 64.toShort()
        osm.tileFileSystemThreads = 4.toShort()
        osm.expirationOverrideDuration = 14L * 24 * 60 * 60 * 1000
    }
}
