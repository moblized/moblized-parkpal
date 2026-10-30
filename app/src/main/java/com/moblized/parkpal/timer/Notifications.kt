package com.moblized.parkpal.timer

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.moblized.parkpal.MainActivity
import com.moblized.parkpal.R
import com.moblized.parkpal.data.ParkingSession
import com.moblized.parkpal.data.SettingsStore
import com.moblized.parkpal.util.Format
import com.moblized.parkpal.util.PayApps

object Notifications {
    private const val CH_COUNTDOWN = "meter_countdown"
    private const val CH_ALERTS = "meter_alerts"
    private const val ID_COUNTDOWN = 10
    private const val ID_ALERT = 11

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CH_COUNTDOWN, "Meter countdown", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows time left on your parking meter"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Meter alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders before and when your meter runs out"
                enableVibration(true)
            },
        )
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        100,
        Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_SPOT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun extendAction(context: Context, minutes: Int, requestCode: Int): NotificationCompat.Action {
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, MeterReceiver::class.java)
                .setAction(MeterAlarms.ACTION_EXTEND)
                .putExtra(MeterAlarms.EXTRA_MINUTES, minutes),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action(0, "+$minutes min", pi)
    }

    private fun payAction(context: Context): NotificationCompat.Action {
        val app = PayApps.byLabel(SettingsStore.settings.value.payApp)
        val pi = PendingIntent.getActivity(
            context,
            200,
            PayApps.intentFor(context, app),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action(0, "Pay in ${app.label}", pi)
    }

    /** Ongoing notification with a live countdown to when the meter ends. */
    @SuppressLint("MissingPermission")
    fun showCountdown(context: Context, s: ParkingSession) {
        val end = s.meterEndsAt ?: return
        if (!canNotify(context) || end <= System.currentTimeMillis()) return
        val n = NotificationCompat.Builder(context, CH_COUNTDOWN)
            .setSmallIcon(R.drawable.ic_stat_parking)
            .setContentTitle("Meter running")
            .setContentText("Ends at ${Format.time(end)} · ${s.title()}")
            .setWhen(end)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp(context))
            .addAction(extendAction(context, 15, 301))
            .addAction(extendAction(context, 30, 302))
            .addAction(payAction(context))
            .build()
        NotificationManagerCompat.from(context).notify(ID_COUNTDOWN, n)
    }

    @SuppressLint("MissingPermission")
    fun showAlert(context: Context, title: String, text: String) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_parking)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .addAction(extendAction(context, 15, 303))
            .addAction(payAction(context))
            .build()
        NotificationManagerCompat.from(context).notify(ID_ALERT, n)
    }

    fun cancelCountdown(context: Context) = NotificationManagerCompat.from(context).cancel(ID_COUNTDOWN)

    fun cancelAlert(context: Context) = NotificationManagerCompat.from(context).cancel(ID_ALERT)

    fun cancelAll(context: Context) {
        cancelCountdown(context)
        cancelAlert(context)
    }
}
