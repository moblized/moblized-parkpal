package com.moblized.parkpal.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.moblized.parkpal.data.Dawarich
import com.moblized.parkpal.data.ParkStore
import com.moblized.parkpal.data.SettingsStore
import com.moblized.parkpal.record.LocationRecorderService
import com.moblized.parkpal.widget.WidgetRefresher

/** Alarms are wiped when the phone restarts, so put the meter reminders back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        ParkStore.init(context)
        SettingsStore.init(context)
        val session = ParkStore.active.value
        if (session != null && (session.meterEndsAt ?: 0L) > System.currentTimeMillis()) {
            MeterAlarms.schedule(context, session, SettingsStore.settings.value.reminderLeadMin)
        }
        WidgetRefresher.refresh(context)

        // Resume recording your timeline. Android only allows it from here with "Allow all the time";
        // otherwise show a notification to tap.
        val s = SettingsStore.settings.value
        if (s.recordLocation && Dawarich.configured(s)) {
            if (LocationRecorderService.hasBackgroundPermission(context)) {
                LocationRecorderService.startIfEnabled(context)
            } else {
                LocationRecorderService.showResumePrompt(context)
            }
        }
    }
}
