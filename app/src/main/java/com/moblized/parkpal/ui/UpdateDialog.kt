package com.moblized.parkpal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moblized.parkpal.update.UpdateInfo
import kotlin.math.roundToInt

@Composable
fun UpdateDialog(
    info: UpdateInfo,
    state: UpdateUi,
    currentBuild: Long,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
) {
    val downloading = state.progress != null
    AlertDialog(
        onDismissRequest = { if (!downloading) onLater() },
        icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
        title = { Text("Update available") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ParkPal build ${info.build} is ready. You have build $currentBuild.")
                if (info.notes.isNotBlank()) {
                    Text(
                        "What's new: ${info.notes}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val progress = state.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("Downloading… ${(progress * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                if (state.needsPermission) {
                    Text(
                        "Allow ParkPal to install updates on the screen that just opened, then come back and it'll continue.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = onUpdate, enabled = !downloading) {
                Text(
                    when {
                        downloading -> "Downloading…"
                        state.apk != null -> "Install"
                        state.error != null -> "Try again"
                        else -> "Update now"
                    },
                )
            }
        },
        dismissButton = {
            Row {
                if (!downloading) TextButton(onClick = onSkip) { Text("Skip") }
                TextButton(onClick = onLater) { Text(if (downloading) "Cancel" else "Later") }
            }
        },
    )
}
