package com.moblized.parkpal.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.moblized.parkpal.data.AppSettings
import com.moblized.parkpal.data.ParkingSession
import com.moblized.parkpal.ui.theme.ParkColors
import com.moblized.parkpal.util.Format
import com.moblized.parkpal.util.Nav
import com.moblized.parkpal.util.PayApps
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt

@Composable
fun MySpotScreen(vm: MainViewModel) {
    val active by vm.active.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val spot = active
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("My spot", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (spot == null) {
            NoSpotCard(onParkHere = vm::parkHere, onPickOnMap = { vm.setTab(AppTab.MAP) })
        } else {
            ActiveSpot(spot, settings, vm)
        }
    }
}

@Composable
private fun NoSpotCard(onParkHere: () -> Unit, onPickOnMap: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.DirectionsCar, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            Text("No spot saved", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Save where you parked so you can walk straight back to it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onParkHere, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.MyLocation, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("I parked here")
            }
            TextButton(onClick = onPickOnMap) { Text("Or pick a garage on the map") }
        }
    }
}

@Composable
private fun ActiveSpot(s: ParkingSession, settings: AppSettings, vm: MainViewModel) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    var confirmEnd by remember { mutableStateOf(false) }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(ParkColors.Parked),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🛻", fontSize = 22.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = s.title(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val address = s.address
                    if (s.placeName != null && address != null) {
                        Text(address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(
                "Parked at ${Format.time(s.startedAt)} · ${Format.ago(now - s.startedAt)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { Nav.navigate(ctx, s.lat, s.lon, walking = true) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.DirectionsWalk, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Walk back")
                }
                OutlinedButton(onClick = { vm.showOnMap(s.lat, s.lon) }) {
                    Icon(Icons.Filled.Map, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Map")
                }
                FilledTonalIconButton(onClick = { Nav.share(ctx, s) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Share spot")
                }
            }
        }
    }

    MeterCard(s, now, settings, vm)
    DetailsCard(s, vm)
    PhotoCard(s, vm)

    OutlinedButton(onClick = { confirmEnd = true }, modifier = Modifier.fillMaxWidth()) {
        Text("I'm back · end parking")
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text("End parking?") },
            text = { Text("Your spot moves to History and any meter reminders are cancelled.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    vm.endParking()
                }) { Text("End parking") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun MeterCard(s: ParkingSession, now: Long, settings: AppSettings, vm: MainViewModel) {
    val ctx = LocalContext.current
    var showCustom by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Timer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Meter timer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            val end = s.meterEndsAt
            if (end == null) {
                Text(
                    "Pay first, then set how long you paid for. You'll get a reminder ${settings.reminderLeadMin} min before it runs out.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15 to "15 min", 30 to "30 min", 60 to "1 h", 120 to "2 h").forEach { (minutes, label) ->
                        FilledTonalButton(onClick = { vm.startMeter(minutes) }) { Text(label) }
                    }
                    OutlinedButton(onClick = { showCustom = true }) { Text("Custom") }
                }
            } else {
                val left = end - now
                val warn = left <= settings.reminderLeadMin * 60_000L
                Text(
                    text = if (left > 0) Format.countdown(left) else "Time's up",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (warn) ParkColors.TooLow else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (left > 0) "Ends at ${Format.time(end)}" else "Ended at ${Format.time(end)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { vm.extendMeter(15) }) { Text("+15 min") }
                    FilledTonalButton(onClick = { vm.extendMeter(30) }) { Text("+30 min") }
                    TextButton(onClick = { vm.clearMeter() }) { Text("Clear") }
                }
                Text(
                    "These buttons only change ParkPal's timer. Add real time in your payment app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PayRow { PayApps.open(ctx, it) }
        }
    }
    if (showCustom) {
        CustomMeterDialog(
            onDismiss = { showCustom = false },
            onConfirm = { minutes ->
                showCustom = false
                vm.startMeter(minutes)
            },
        )
    }
}

@Composable
private fun CustomMeterDialog(onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var minutes by remember { mutableFloatStateOf(90f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How long did you pay for?") },
        text = {
            Column {
                Text(Format.duration(minutes.roundToInt() * 60_000L), style = MaterialTheme.typography.headlineSmall)
                Slider(
                    value = minutes,
                    onValueChange = { minutes = (it / 5f).roundToInt() * 5f },
                    valueRange = 5f..600f,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(minutes.roundToInt()) }) { Text("Start timer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DetailsCard(s: ParkingSession, vm: MainViewModel) {
    // Local text state keeps typing smooth; every change is saved right away.
    var level by remember(s.id) { mutableStateOf(s.level) }
    var note by remember(s.id) { mutableStateOf(s.note) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Details", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = level,
                onValueChange = { v ->
                    level = v
                    vm.updateSpot { it.copy(level = v) }
                },
                label = { Text("Level / row / space") },
                placeholder = { Text("e.g. P3, row G, space 214") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = note,
                onValueChange = { v ->
                    note = v
                    vm.updateSpot { it.copy(note = v) }
                },
                label = { Text("Note") },
                placeholder = { Text("Near the elevator, green pillar…") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PhotoCard(s: ParkingSession, vm: MainViewModel) {
    val ctx = LocalContext.current
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val path = pendingPath
        if (ok && path != null) vm.setPhoto(path) else path?.let { File(it).delete() }
        pendingPath = null
    }
    val takePhoto = {
        val dir = File(ctx.filesDir, "photos").apply { mkdirs() }
        val file = File(dir, "spot_${System.currentTimeMillis()}.jpg")
        pendingPath = file.absolutePath
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        try {
            camera.launch(uri)
        } catch (e: ActivityNotFoundException) {
            vm.toast("No camera app found")
        }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Photo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val path = s.photoPath
            if (path != null && File(path).exists()) {
                AsyncImage(
                    model = File(path),
                    contentDescription = "Photo of your parking spot",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = takePhoto) { Text("Retake") }
                    TextButton(onClick = { vm.setPhoto(null) }) { Text("Remove") }
                }
            } else {
                OutlinedButton(onClick = takePhoto, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Snap the level sign or your spot")
                }
            }
        }
    }
}
