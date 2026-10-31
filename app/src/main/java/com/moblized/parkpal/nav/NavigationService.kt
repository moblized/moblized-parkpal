package com.moblized.parkpal.nav

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.moblized.parkpal.MainActivity
import com.moblized.parkpal.R
import com.moblized.parkpal.data.Destination
import com.moblized.parkpal.data.ParkStore
import com.moblized.parkpal.data.ParkingPlace
import com.moblized.parkpal.data.SettingsStore
import com.moblized.parkpal.util.LocationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Where to, how, and what to offer when you get there. */
data class NavRequest(
    val dest: Destination,
    val mode: TravelMode,
    /** Set when driving to a parking place: offer to save it as your spot on arrival. */
    val parking: ParkingPlace? = null,
    /** Where you're going after parking, so arrival can offer to walk there. */
    val after: Destination? = null,
    /** Offer "Park here" on arrival (driving somewhere you'll leave the truck). */
    val offerParking: Boolean = false,
    /** Walking back to your truck: offer to end parking on arrival. */
    val toCar: Boolean = false,
)

enum class NavStatus { ROUTING, NAVIGATING, REROUTING, ARRIVED, FAILED }

data class NavState(
    val request: NavRequest,
    val status: NavStatus,
    val route: NavRoute? = null,
    /** Bumped for every new route (first one and reroutes), so the map redraws the line. */
    val routeVersion: Int = 0,
    val progress: Progress? = null,
    val speed: Float = 0f,
    val error: String? = null,
)

/** The navigation in progress, shared by the service and the screen. */
object NavSession {
    private val _state = MutableStateFlow<NavState?>(null)
    val state: StateFlow<NavState?> = _state.asStateFlow()

    @Volatile
    internal var pending: NavRequest? = null

    internal fun set(s: NavState?) {
        _state.value = s
    }

    internal fun update(transform: (NavState) -> NavState) {
        _state.update { it?.let(transform) }
    }

    /** Close the arrival (or error) card. */
    fun dismiss() {
        val s = _state.value ?: return
        if (s.status == NavStatus.ARRIVED || s.status == NavStatus.FAILED) _state.value = null
    }
}

/**
 * Turn-by-turn navigation that keeps going with the screen off or another app open (a foreground service,
 * like Google Maps), speaking each turn.
 */
class NavigationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var client: FusedLocationProviderClient
    private lateinit var voice: NavVoice
    private var request: NavRequest? = null
    private var engine: NavEngine? = null
    private var lastFix: Location? = null
    private var routingJob: Job? = null
    private var arrivalJob: Job? = null
    private var lastReroute = 0L
    private var lastNotified = 0L
    private var lastNotifiedStep = -1
    private var updating = false
    /** Step index -> the closest announcement already spoken for it (1 far, 2 approaching, 3 now). */
    private val announced = HashMap<Int, Int>()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onFix(it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        ParkStore.init(this)
        client = LocationServices.getFusedLocationProviderClient(this)
        voice = NavVoice(this)
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            NavSession.set(null)
            finish()
            return START_NOT_STICKY
        }
        val req = NavSession.pending
        NavSession.pending = null
        if (req == null) {
            if (request == null) stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification("Starting directions…", req.dest.title),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        } catch (e: Exception) {
            NavSession.set(NavState(req, NavStatus.FAILED, error = "Android didn't allow navigation to start. Check location permission."))
            stopSelf()
            return START_NOT_STICKY
        }
        begin(req)
        return START_NOT_STICKY
    }

    private fun begin(req: NavRequest) {
        routingJob?.cancel()
        arrivalJob?.cancel()
        request = req
        engine = null
        announced.clear()
        lastNotifiedStep = -1
        NavSession.set(NavState(req, NavStatus.ROUTING))
        startUpdates(req.mode)
        routingJob = scope.launch {
            val here = lastFix ?: LocationHelper.current(this@NavigationService)
            if (here == null) {
                fail("Couldn't get your location. Check that location is on and allowed for ParkPal.")
                return@launch
            }
            fetchRoute(here, initial = true)
        }
    }

    private suspend fun fetchRoute(from: Location, initial: Boolean) {
        val req = request ?: return
        try {
            val heading = if (from.hasBearing() && from.speed > 3f) from.bearing else null
            val route = NavRouting.route(from.latitude, from.longitude, req.dest.lat, req.dest.lon, req.mode, heading)
            engine = NavEngine(route)
            announced.clear()
            if (!initial) announced[0] = 3
            NavSession.update { it.copy(status = NavStatus.NAVIGATING, route = route, routeVersion = it.routeVersion + 1, error = null) }
            if (initial) {
                val first = route.steps.firstOrNull()
                if (first != null) say(first.text + (thenHint(route, 0) ?: ""))
                announced[0] = 3
            }
            onFix(lastFix ?: from)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (initial || engine == null) {
                fail("Couldn't get directions: ${e.message ?: "network error"}")
            } else {
                // Keep following the old route; the next fixes will try again.
                NavSession.update { it.copy(status = NavStatus.NAVIGATING) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates(mode: TravelMode) {
        if (updating || !LocationHelper.hasPermission(this)) return
        val interval = if (mode == TravelMode.WALK) 2_000L else 1_000L
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, interval)
            .setMinUpdateIntervalMillis(interval / 2)
            .build()
        try {
            client.requestLocationUpdates(req, callback, Looper.getMainLooper())
            updating = true
        } catch (e: Exception) {
            fail("Couldn't follow your location: ${e.message}")
        }
    }

    private fun stopUpdates() {
        if (!updating) return
        client.removeLocationUpdates(callback)
        updating = false
    }

    private fun onFix(loc: Location) {
        lastFix = loc
        val req = request ?: return
        val e = engine ?: return
        val state = NavSession.state.value ?: return
        if (state.status == NavStatus.ARRIVED || state.status == NavStatus.FAILED) return
        val heading = if (loc.hasBearing() && loc.speed > 2f) loc.bearing else null
        val (p, lost) = e.update(loc.latitude, loc.longitude, loc.accuracy, loc.speed, heading)
        NavSession.update { it.copy(progress = p, speed = if (loc.hasSpeed()) loc.speed else 0f) }

        val toDest = NavRouting.meters(loc.latitude, loc.longitude, req.dest.lat, req.dest.lon)
        val walk = req.mode == TravelMode.WALK
        if (toDest <= (if (walk) 15.0 else 35.0) || (p.remainingMeters <= (if (walk) 10.0 else 25.0) && p.offRoute < 60)) {
            arrive()
            return
        }
        val now = System.currentTimeMillis()
        if (lost && routingJob?.isActive != true && now - lastReroute > 8_000L) {
            lastReroute = now
            NavSession.update { it.copy(status = NavStatus.REROUTING) }
            notify(force = true)
            routingJob = scope.launch { fetchRoute(loc, initial = false) }
            return
        }
        announce(e.route, p, loc.speed)
        notify(force = p.nextStep != lastNotifiedStep)
    }

    private fun announce(route: NavRoute, p: Progress, speed: Float) {
        val i = p.nextStep
        val step = route.steps.getOrNull(i) ?: return
        val d = p.toNextStep
        val walk = route.mode == TravelMode.WALK
        val v = if (speed > 1f) speed.toDouble() else if (walk) 1.4 else 13.0
        val near = if (walk) 12.0 else (v * 8).coerceIn(50.0, 250.0)
        val mid = if (walk) 60.0 else (v * 30).coerceIn(250.0, 1000.0)
        val far = if (!walk && v > 22) 3200.0 else 0.0
        val done = announced[i] ?: 0
        val hint = thenHint(route, i) ?: ""
        when {
            d <= near && done < 3 -> {
                announced[i] = 3
                if (step.kind != ManeuverKind.ARRIVE) say(step.text + hint)
            }
            d <= mid && d > near * 1.6 && done < 2 -> {
                announced[i] = 2
                say(
                    if (step.kind == ManeuverKind.ARRIVE) {
                        "In ${NavText.spoken(d)}, you'll arrive at ${request?.dest?.title ?: "your destination"}"
                    } else {
                        "In ${NavText.spoken(d)}, ${lowerFirst(step.text)}$hint"
                    },
                )
            }
            far > 0 && d <= far && d > mid * 1.6 && done < 1 -> {
                announced[i] = 1
                if (step.kind != ManeuverKind.ARRIVE) say("In ${NavText.spoken(d)}, ${lowerFirst(step.text)}")
            }
        }
    }

    /** ", then turn right" when the turn after this one comes right away. */
    private fun thenHint(route: NavRoute, i: Int): String? {
        val step = route.steps.getOrNull(i) ?: return null
        val next = route.steps.getOrNull(i + 1) ?: return null
        if (next.kind == ManeuverKind.ARRIVE) return null
        val gap = next.startDist - step.startDist
        return if (gap < (if (route.mode == TravelMode.WALK) 25.0 else 120.0)) ", then ${next.short}" else null
    }

    private fun lowerFirst(s: String): String =
        if (s.length > 1 && s[0].isUpperCase() && !s[1].isUpperCase()) s[0].lowercaseChar() + s.substring(1) else s

    private fun say(text: String) {
        if (SettingsStore.settings.value.navVoice) voice.speak(text)
    }

    private fun arrive() {
        val req = request ?: return
        stopUpdates()
        routingJob?.cancel()
        NavSession.update { it.copy(status = NavStatus.ARRIVED) }
        say("You have arrived at ${req.dest.title}")
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, notification("You've arrived", req.dest.title))
        // Let the voice finish, then step out of the way; the arrival card stays on screen.
        arrivalJob = scope.launch {
            delay(8_000L)
            ServiceCompat.stopForeground(this@NavigationService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fail(message: String) {
        val req = request ?: return
        stopUpdates()
        NavSession.set(NavState(req, NavStatus.FAILED, error = message))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finish() {
        stopUpdates()
        voice.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notify(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotified < 5_000L) return
        val s = NavSession.state.value ?: return
        val route = s.route ?: return
        val p = s.progress ?: return
        lastNotified = now
        lastNotifiedStep = p.nextStep
        val step = route.steps.getOrNull(p.nextStep) ?: return
        val title = if (s.status == NavStatus.REROUTING) "Finding a new route…" else "${NavText.short(p.toNextStep)} · ${step.text}"
        val eta = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now + (p.remainingSeconds * 1000).toLong()))
        val text = "${NavText.duration(p.remainingSeconds)} · ${NavText.short(p.remainingMeters)} · arrive $eta"
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(title, text))
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 500,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val end = PendingIntent.getService(
            this, 501,
            Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_parking)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "End", end)
            .build()
    }

    override fun onDestroy() {
        stopUpdates()
        voice.shutdown()
        scope.cancel()
        val s = NavSession.state.value
        // Stopped by the system mid-trip: don't leave a frozen navigation screen behind.
        if (s != null && s.status != NavStatus.ARRIVED && s.status != NavStatus.FAILED) NavSession.set(null)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "navigation"
        private const val NOTIFICATION_ID = 30
        private const val ACTION_START = "com.moblized.parkpal.NAV_START"
        private const val ACTION_STOP = "com.moblized.parkpal.NAV_STOP"

        fun start(context: Context, request: NavRequest) {
            NavSession.pending = request
            NavSession.set(NavState(request, NavStatus.ROUTING))
            ContextCompat.startForegroundService(
                context,
                Intent(context, NavigationService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            NavSession.set(null)
            try {
                context.startService(Intent(context, NavigationService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
                // Not running (or not allowed from the background): nothing to stop.
            }
        }

        private fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Navigation", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Turn-by-turn directions while ParkPal is navigating"
                    setShowBadge(false)
                },
            )
        }
    }
}
