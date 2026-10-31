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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moblized.parkpal.data.AppSettings
import com.moblized.parkpal.data.Dawarich
import com.moblized.parkpal.data.DawarichSync
import com.moblized.parkpal.data.ParkStore
import com.moblized.parkpal.data.ParkingServer
import com.moblized.parkpal.data.SettingsStore
import com.moblized.parkpal.data.ThemeMode
import com.moblized.parkpal.timer.MeterAlarms
import com.moblized.parkpal.util.Format
import com.moblized.parkpal.util.PayApps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

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

        Section("Search this area radius") {
            ChoiceChips(
                options = listOf(400 to "¼ mi", 800 to "½ mi", 1600 to "1 mi", 3200 to "2 mi"),
                selected = s.radiusMeters,
                onSelect = { r -> SettingsStore.update { it.copy(radiusMeters = r) } },
            )
        }

        Section("Destinations") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { SettingsStore.update { it.copy(autoFindParking = !it.autoFindParking) } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Find parking automatically", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = s.autoFindParking,
                    onCheckedChange = { on -> SettingsStore.update { it.copy(autoFindParking = on) } },
                )
            }
            Hint(
                if (s.autoFindParking) {
                    "When you pick where you're going, ParkPal looks for parking around it right away."
                } else {
                    "Pick a destination, then tap Find parking when you need it."
                },
            )
            Text("Look for parking within:", style = MaterialTheme.typography.bodyMedium)
            ChoiceChips(
                options = listOf(400 to "¼ mi · 5 min walk", 800 to "½ mi · 10 min", 1600 to "1 mi · 20 min"),
                selected = s.walkRadiusMeters,
                onSelect = { r -> SettingsStore.update { it.copy(walkRadiusMeters = r) } },
            )
            Hint("If there's very little parking that fits, ParkPal looks a bit farther out.")
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

        Section("Parking server") {
            ParkingServerSettings(s.serverUrl, s.serverToken)
        }

        Section("Dawarich") {
            DawarichSettings(s)
        }

        Section("Map style") {
            var key by remember { mutableStateOf(s.cartoKey) }
            Text(
                if (s.cartoKey.isBlank()) "Using the standard OpenStreetMap map." else "Using the CARTO map style.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Hint(
                "For a cleaner, Google Maps-like style with a real dark map, paste a free CARTO key here. " +
                    "It's free for personal use: request it on CARTO's site and it arrives by email. Leave blank for the standard map.",
            )
            OutlinedTextField(
                value = key,
                onValueChange = { v ->
                    key = v
                    SettingsStore.update { it.copy(cartoKey = v.trim()) }
                },
                label = { Text("CARTO key (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(MapTiles.CARTO_KEY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) {
                Text("Get a free key")
            }
            Hint("If the map shows \"API key required\", the key is missing or wrong. Clear the box to go back to the standard map.")
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
                "ParkPal shows parking that fits your vehicle on your truck's screen. Tap Search to pick a destination " +
                    "(your recent ones are listed) and see parking near it, with Navigate and Park here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Hint("Android Auto only lets you type while parked, so search before you set off or pick a recent destination.")
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

/** Connection to your Dawarich location-history server, and what ParkPal shares with it. */
@Composable
private fun DawarichSettings(s: AppSettings) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(s.dawarichUrl) }
    var key by remember { mutableStateOf(s.dawarichKey) }
    var showKey by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text(
        if (Dawarich.configured(s)) "Connected to your Dawarich." else "Link ParkPal with your self-hosted Dawarich.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = url,
        onValueChange = { v ->
            url = v
            result = null
            SettingsStore.update { it.copy(dawarichUrl = v.trim()) }
        },
        label = { Text("Dawarich address") },
        placeholder = { Text("dawarich.yourdomain.org") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = key,
        onValueChange = { v ->
            key = v
            result = null
            SettingsStore.update { it.copy(dawarichKey = v.trim()) }
        },
        label = { Text("API key") },
        singleLine = true,
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showKey = !showKey }) {
                Icon(
                    if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (showKey) "Hide key" else "Show key",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Hint("Your API key is on your account page in Dawarich.")

    ToggleRow(
        "Add parking to my timeline",
        "Each stop shows up as a \"Parked · …\" visit when you end parking.",
        s.dawarichVisits,
    ) { on -> SettingsStore.update { it.copy(dawarichVisits = on) } }
    ToggleRow(
        "Use my places as destinations",
        "Your Dawarich places show in Where to? and Android Auto.",
        s.dawarichPlaces,
    ) { on ->
        SettingsStore.update { it.copy(dawarichPlaces = on) }
        scope.launch { DawarichSync.refreshPlaces(force = true) }
    }
    ToggleRow(
        "Save starred garages as places",
        "Starring a garage also adds it to Dawarich.",
        s.dawarichFavorites,
    ) { on ->
        SettingsStore.update { it.copy(dawarichFavorites = on) }
        if (on) DawarichSync.kick(ctx)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            enabled = url.isNotBlank() && key.isNotBlank() && !testing,
            onClick = {
                testing = true
                result = null
                scope.launch {
                    try {
                        val email = Dawarich.me(url, key)
                        val count = Dawarich.places(url, key).count {
                            !it.note.startsWith(Dawarich.PARKPAL_NOTE) && !it.name.startsWith("Parked · ")
                        }
                        resultOk = true
                        result = "Connected" + (if (email.isNotBlank()) " as $email" else "") +
                            " · " + (if (count == 1) "1 place" else "$count places")
                        DawarichSync.refreshPlaces(force = true)
                        DawarichSync.kick(ctx)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        resultOk = false
                        result = "Couldn't connect: ${e.message ?: "no answer"}"
                    } finally {
                        testing = false
                    }
                }
            },
        ) {
            Text(if (testing) "Testing…" else "Test connection")
        }
        if (testing) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
    result?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = if (resultOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Address and token for your own ParkPal parking server, with a connection test. */
@Composable
private fun ParkingServerSettings(savedUrl: String, savedToken: String) {
    var url by remember { mutableStateOf(savedUrl) }
    var token by remember { mutableStateOf(savedToken) }
    var showToken by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultOk by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text(
        if (savedUrl.isBlank()) {
            "Not set up: using the free public servers, which are often overloaded."
        } else {
            "Using your server first, with the public servers as a backup."
        },
        style = MaterialTheme.typography.bodyMedium,
    )
    Hint("Run the ParkPal parking server on your TrueNAS (see server/README.md in the GitHub repo) for fast, reliable parking.")
    OutlinedTextField(
        value = url,
        onValueChange = { v ->
            url = v
            result = null
            SettingsStore.update { it.copy(serverUrl = v.trim()) }
        },
        label = { Text("Server address") },
        placeholder = { Text("parkpal.yourdomain.org") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = token,
        onValueChange = { v ->
            token = v
            result = null
            SettingsStore.update { it.copy(serverToken = v.trim()) }
        },
        label = { Text("Token") },
        singleLine = true,
        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { showToken = !showToken }) {
                Icon(
                    if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (showToken) "Hide token" else "Show token",
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            enabled = url.isNotBlank() && !testing,
            onClick = {
                testing = true
                result = null
                scope.launch {
                    try {
                        val st = ParkingServer.status(url, token)
                        val google = if (st.googleEnabled) {
                            String.format(
                                Locale.US, "\nGoogle parking: on, %,d of %,d searches used this month",
                                st.googleUsed, st.googleLimit,
                            )
                        } else {
                            ""
                        }
                        resultOk = st.ready || st.googleEnabled
                        result = when {
                            st.ready -> buildString {
                                append("Connected: ").append(String.format(Locale.US, "%,d", st.places)).append(" parking places")
                                st.updated?.let { append(", updated ").append(it.take(10)) }
                                if (st.busy) append(". ").append(st.message)
                                append(google)
                            }
                            else -> "Connected, but still preparing data: ${st.message}$google"
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        resultOk = false
                        result = "Couldn't connect: ${e.message ?: "no answer"}"
                    } finally {
                        testing = false
                    }
                }
            },
        ) {
            Text(if (testing) "Testing…" else "Test connection")
        }
        if (testing) {
            Spacer(Modifier.width(12.dp))
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
    result?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            color = if (resultOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
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
