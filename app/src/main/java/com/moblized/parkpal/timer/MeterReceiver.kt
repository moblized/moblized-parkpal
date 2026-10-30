package com.moblized.parkpal.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.moblized.parkpal.data.ParkActions
import com.moblized.parkpal.data.ParkStore
import com.moblized.parkpal.data.SettingsStore
import com.moblized.parkpal.widget.WidgetRefresher

class MeterReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ParkStore.init(context)
        SettingsStore.init(context)
        val session = ParkStore.active.value ?: return
        when (intent.action) {
            MeterAlarms.ACTION_WARN -> {
                val end = session.meterEndsAt ?: return
                val minsLeft = ((end - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)
                Notifications.showAlert(
                    context,
                    title = "Meter ends in $minsLeft min",
                    text = "${session.title()} · ends at ${com.moblized.parkpal.util.Format.time(end)}",
                )
            }
            MeterAlarms.ACTION_EXPIRED -> {
                Notifications.cancelCountdown(context)
                Notifications.showAlert(
                    context,
                    title = "Your parking meter ran out",
                    text = session.title(),
                )
                WidgetRefresher.refresh(context)
            }
            MeterAlarms.ACTION_EXTEND -> {
                val minutes = intent.getIntExtra(MeterAlarms.EXTRA_MINUTES, 15)
                ParkActions.extendMeter(context, minutes)
            }
        }
    }
}
