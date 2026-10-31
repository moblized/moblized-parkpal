package com.moblized.parkpal.record

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.moblized.parkpal.MainActivity
import com.moblized.parkpal.R
import com.moblized.parkpal.data.Dawarich
import com.moblized.parkpal.data.DawarichSync
import com.moblized.parkpal.data.ParkStore
import com.moblized.parkpal.data.RecordMode
import com.moblized.parkpal.data.SettingsStore
import com.moblized.parkpal.util.LocationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Records your location in the background for your Dawarich timeline, like a tracker app (OwnTracks etc.).
 * In Balanced mode it saves battery while you're still and records closely while you're driving.
 */
class LocationRecorderService : Service() {
    private lateinit var client: FusedLocationProviderClient
    private var fastMode: Boolean? = null
    private var lastMovingAt = 0L
    private var lastFix: Location? = null
    private var lastUploadKick = 0L

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { onFix(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        ParkStore.init(this)
        DawarichSync.init(this)
        PointBuffer.init(this)
        client = LocationServices.getFusedLocationProviderClient(this)
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SettingsStore.update { it.copy(recordLocation = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, notification(this))
            }
        } catch (e: Exception) {
            // Not allowed right now (e.g. started from the background without "Allow all the time").
            stopSelf()
            return START_NOT_STICKY
        }
        if (!LocationHelper.hasPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        fastMode = null
        requestUpdates(fast = false)
        _running.value = true
        return START_STICKY
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        _running.value = false
        DawarichSync.kick(this) // send what's left
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates(fast: Boolean) {
        if (fastMode == fast) return
        fastMode = fast
        val request = when (SettingsStore.settings.value.recordMode) {
            RecordMode.PRECISE ->
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L).setMinUpdateDistanceMeters(5f).build()
            RecordMode.BATTERY ->
                LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5 * 60_000L).setMinUpdateDistanceMeters(100f).build()
            RecordMode.BALANCED -> if (fast) {
                LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L).setMinUpdateDistanceMeters(15f).build()
            } else {
                LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 60_000L).setMinUpdateDistanceMeters(30f).build()
            }
        }
        client.removeLocationUpdates(callback)
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun onFix(loc: Location) {
        val maxAccuracy = if (SettingsStore.settings.value.recordMode == RecordMode.BATTERY) 500f else 200f
        if (loc.hasAccuracy() && loc.accuracy > maxAccuracy) return

        // Moving? Use the reported speed, or work it out from the last point.
        val prev = lastFix
        val speed = when {
            loc.hasSpeed() -> loc.speed
            prev != null && loc.time > prev.time -> prev.distanceTo(loc) / ((loc.time - prev.time) / 1000f)
            else -> 0f
        }
        lastFix = loc
        val now = System.currentTimeMillis()
        if (speed > MOVING_SPEED_MPS) lastMovingAt = now
        if (SettingsStore.settings.value.recordMode == RecordMode.BALANCED) {
            requestUpdates(fast = now - lastMovingAt < STILL_AFTER_MS)
        }

        val battery = getSystemService(BatteryManager::class.java)
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }?.div(100f)
        val charging = battery?.isCharging
        PointBuffer.add(loc, level, charging, deviceId())

        if (PointBuffer.pending.value >= UPLOAD_EVERY_POINTS || now - lastUploadKick > UPLOAD_EVERY_MS) {
            lastUploadKick = now
            DawarichSync.kick(this)
        }
    }

    private fun deviceId(): String = "parkpal-" + Build.MODEL.replace(Regex("[^A-Za-z0-9-]"), "")

    companion object {
        const val ACTION_STOP = "com.moblized.parkpal.STOP_RECORDING"
        private const val CHANNEL = "location_recording"
        private const val CHANNEL_RESUME = "location_recording_resume"
        private const val NOTIFICATION_ID = 20
        private const val RESUME_NOTIFICATION_ID = 21
        private const val MOVING_SPEED_MPS = 4f          // about 9 mph
        private const val STILL_AFTER_MS = 3 * 60_000L   // back to battery-saving after 3 still minutes
        private const val UPLOAD_EVERY_POINTS = 60
        private const val UPLOAD_EVERY_MS = 10 * 60_000L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Starts recording if it's turned on, Dawarich is set up and location is allowed. */
        fun startIfEnabled(context: Context) {
            val s = SettingsStore.settings.value
            if (!s.recordLocation || !Dawarich.configured(s) || !LocationHelper.hasPermission(context)) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, LocationRecorderService::class.java))
            } catch (e: Exception) {
                // Android won't allow it from the background right now; recording resumes when the app opens.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationRecorderService::class.java))
        }

        fun hasBackgroundPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        /** After a restart without "Allow all the time": a notification to tap to resume recording. */
        fun showResumePrompt(context: Context) {
            createChannel(context)
            val open = PendingIntent.getActivity(
                context, 400,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(context, CHANNEL_RESUME)
                .setSmallIcon(R.drawable.ic_stat_parking)
                .setContentTitle("Location recording paused")
                .setContentText("Tap to resume recording your timeline")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) {
                nm?.notify(RESUME_NOTIFICATION_ID, n)
            }
        }

        private fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Location recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while ParkPal records your location for Dawarich"
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_RESUME, "Location recording paused", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        private fun notification(context: Context): Notification {
            val open = PendingIntent.getActivity(
                context, 401,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val stop = PendingIntent.getService(
                context, 402,
                Intent(context, LocationRecorderService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_parking)
                .setContentTitle("Recording your timeline")
                .setContentText("Sending your location to Dawarich")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(open)
                .addAction(0, "Stop", stop)
                .build()
        }
    }
}
