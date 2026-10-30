package com.moblized.parkpal.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.moblized.parkpal.data.ParkingSession

object MeterAlarms {
    const val ACTION_WARN = "com.moblized.parkpal.METER_WARN"
    const val ACTION_EXPIRED = "com.moblized.parkpal.METER_EXPIRED"
    const val ACTION_EXTEND = "com.moblized.parkpal.METER_EXTEND"
    const val EXTRA_MINUTES = "minutes"

    private const val REQ_WARN = 1
    private const val REQ_EXPIRED = 2

    /** Schedules the "running low" and "expired" reminders and shows the countdown notification. */
    fun schedule(context: Context, session: ParkingSession, leadMinutes: Int) {
        val app = context.applicationContext
        cancelAlarms(app)
        Notifications.cancelAlert(app)
        val end = session.meterEndsAt ?: return
        val now = System.currentTimeMillis()
        val warnAt = end - leadMinutes * 60_000L
        if (warnAt > now) set(app, warnAt, pending(app, ACTION_WARN, REQ_WARN))
        if (end > now) set(app, end, pending(app, ACTION_EXPIRED, REQ_EXPIRED))
        Notifications.showCountdown(app, session)
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        cancelAlarms(app)
        Notifications.cancelAll(app)
    }

    private fun cancelAlarms(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(context, ACTION_WARN, REQ_WARN))
        am.cancel(pending(context, ACTION_EXPIRED, REQ_EXPIRED))
    }

    private fun set(context: Context, at: Long, pi: PendingIntent) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exactAllowed) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun pending(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, MeterReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
