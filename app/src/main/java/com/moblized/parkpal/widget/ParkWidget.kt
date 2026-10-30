package com.moblized.parkpal.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.moblized.parkpal.MainActivity
import com.moblized.parkpal.data.ParkStore
import com.moblized.parkpal.data.ParkingSession
import com.moblized.parkpal.util.Format
import com.moblized.parkpal.util.Nav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ParkWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        ParkStore.init(context)
        provideContent {
            val session by ParkStore.active.collectAsState()
            GlanceTheme {
                WidgetBody(session)
            }
        }
    }
}

@Composable
private fun WidgetBody(session: ParkingSession?) {
    val ctx = LocalContext.current
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    val openApp = Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_SPOT).addFlags(flags)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(20.dp)
            .padding(14.dp)
            .clickable(actionStartActivity(openApp)),
    ) {
        Text(
            text = "ParkPal",
            style = TextStyle(color = GlanceTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp),
        )
        Spacer(GlanceModifier.height(4.dp))
        if (session == null) {
            Text(
                text = "No spot saved",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp),
            )
            Spacer(GlanceModifier.height(8.dp))
            Button(
                text = "I parked here",
                onClick = actionStartActivity(
                    Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_PARK_HERE).addFlags(flags),
                ),
            )
        } else {
            Text(
                text = session.title(),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp),
                maxLines = 2,
            )
            val details = buildString {
                append("Parked ").append(Format.time(session.startedAt))
                if (session.level.isNotBlank()) append(" · ").append(session.level)
            }
            Text(
                text = details,
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
                maxLines = 1,
            )
            val end = session.meterEndsAt
            if (end != null) {
                val expired = end <= System.currentTimeMillis()
                Text(
                    text = if (expired) "Meter ran out at ${Format.time(end)}" else "Meter ends ${Format.time(end)}",
                    style = TextStyle(
                        color = if (expired) GlanceTheme.colors.error else GlanceTheme.colors.secondary,
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp,
                    ),
                )
            }
            Spacer(GlanceModifier.height(8.dp))
            Button(
                text = "Walk back",
                onClick = actionStartActivity(Nav.intent(session.lat, session.lon, walking = true)),
            )
        }
    }
}

class ParkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ParkWidget()
}

object WidgetRefresher {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { ParkWidget().updateAll(app) } }
    }
}
