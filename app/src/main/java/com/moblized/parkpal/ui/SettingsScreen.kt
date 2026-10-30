@file:OptIn(ExperimentalMaterial3Api::class)

package com.moblized.parkpal.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moblized.parkpal.data.ParkStore
import com.moblized.parkpal.data.SettingsStore
import com.moblized.parkpal.data.ThemeMode
import com.moblized.parkpal.timer.MeterAlarms
import com.moblized.parkpal.util.Format
import com.moblized.parkpal.util.PayApps

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        Section("Your vehicle") {
            var name by remember { mutableStateOf(s.vehicleName) }
            OutlinedTextField(
                value = name,
                onValueChange = { v ->
                    name = v.take(24)
                    if (name.isNotBlank()) SettingsStore.update { it.copy(vehicleName = name.trim()) }
                },
                label = { Text("What to call it") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Stepper(
                label = "Height",
                value = Format.ftIn(s.vehicleHeightIn),
                onMinus = { SettingsStore.update { it.copy(vehicleHeightIn = (it.vehicleHeightIn - 1).coerceAtLeast(48)) } },
                onPlus = { SettingsStore.update { it.copy(vehicleHeightIn = (it.vehicleHeightIn + 1).coerceAtMost(156)) } },
            )
            Hint("Measure from the ground to the highest point, including roof marker lights, racks or antennas.")
            Stepper(
                label = "Safety margin",
                value = "${s.marginIn} in",
                onMinus = { SettingsStore.update { it.copy(marginIn = (it.marginIn - 1).coerceAtLeast(0)) } },
                onPlus = { SettingsStore.update { it.copy(marginIn = (it.marginIn + 1).coerceAtMost(12)) } },
            )
            Hint("Garages count as a fit only with at least ${Format.ftIn(s.vehicleHeightIn + s.marginIn)} of clearance.")
        }

        Section("Search radius") {
            ChoiceChips(
                options = listOf(400 to "¼ mi", 800 to "½ mi", 1600 to "1 mi", 3200 to "2 mi"),
                selected = s.radiusMeters,
                onSelect = { r -> SettingsStore.update { it.copy(radiusMeters = r) } },
            )
        }

        Section("Meter reminders") {
            Text("Remind me before the meter runs out:", style = MaterialTheme.typography.bodyMedium)
            ChoiceChips(
                options = listOf(5 to "5 min", 10 to "10 min", 15 to "15 min", 20 to "20 min", 30 to "30 min"),
                selected = s.reminderLeadMin,
                onSelect = { m ->
                    SettingsStore.update { it.copy(reminderLeadMin = m) }
                    ParkStore.active.value?.let { spot -> if (spot.meterEndsAt != null) MeterAlarms.schedule(ctx, spot, m) }
                },
            )
            NotificationPermissionButton()
            ExactAlarmButton()
        }

        Section("Paying") {
            PayApps.all.forEach { app ->
                val installed = remember(app) { PayApps.isInstalled(ctx, app) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { SettingsStore.update { it.copy(payApp = app.label) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = s.payApp == app.label,
                        onClick = { SettingsStore.update { it.copy(payApp = app.label) } },
                    )
                    Text(app.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(
                        if (installed) "Installed" else "Not installed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Hint("Used for the Pay button on meter notifications.")
        }

        Section("Appearance") {
            ThemeMode.entries.forEach { mode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { SettingsStore.update { it.copy(themeMode = mode) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = s.themeMode == mode,
                        onClick = { SettingsStore.update { it.copy(themeMode = mode) } },
                    )
                    Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Hint("The home-screen icon switches to a dark version too. It changes when you leave the app.")
        }

        Section("Android Auto") {
            Text(
                "ParkPal shows nearby parking that fits your vehicle on your truck's screen, with directions and Park here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Hint(
                "Because it isn't from the Play Store, turn on Unknown sources once: open Android Auto settings, " +
                    "tap Version about 10 times to unlock developer settings, then use the ⋮ menu → Developer settings → Unknown sources.",
            )
        }

        Section("About") {
            val update by vm.update.collectAsStateWithLifecycle()
            val newer = update.info
            Text("ParkPal build ${vm.currentBuild}", style = MaterialTheme.typography.bodyLarge)
            if (newer != null) {
                OutlinedButton(onClick = vm::showUpdateDialog) { Text("Update to build ${newer.build}") }
            } else {
                OutlinedButton(onClick = { vm.checkForUpdate(manual = true) }, enabled = !update.checking) {
                    Text(if (update.checking) "Checking…" else "Check for updates")
                }
            }
            Hint(
                "Map and parking data © OpenStreetMap contributors. Data can be missing or out of date, " +
                    "so always check posted signs and height bars before you pull in.",
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        FilledTonalIconButton(onClick = onMinus) { Icon(Icons.Filled.Remove, contentDescription = "Less") }
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 64.dp),
        )
        FilledTonalIconButton(onClick = onPlus) { Icon(Icons.Filled.Add, contentDescription = "More") }
    }
}

@Composable
private fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun NotificationPermissionButton() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) {
        OutlinedButton(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
            Text("Allow notifications for reminders")
        }
    }
}

@Composable
private fun ExactAlarmButton() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val ctx = LocalContext.current
    val am = ctx.getSystemService(AlarmManager::class.java) ?: return
    if (am.canScheduleExactAlarms()) return
    OutlinedButton(onClick = {
        ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
    }) {
        Text("Allow on-time reminders")
    }
}
