package com.moblized.parkpal.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moblized.parkpal.data.AppSettings
import com.moblized.parkpal.data.Clearance
import com.moblized.parkpal.data.FeeInfo
import com.moblized.parkpal.data.FitStatus
import com.moblized.parkpal.data.ParkingPlace
import com.moblized.parkpal.ui.theme.ParkColors
import com.moblized.parkpal.util.PayApp
import com.moblized.parkpal.util.PayApps

fun statusColor(place: ParkingPlace, status: FitStatus): Color = when (status) {
    FitStatus.TOO_LOW -> ParkColors.TooLow
    FitStatus.CHECK_SIGN -> ParkColors.Check
    else -> if (place.fee == FeeInfo.FREE) ParkColors.Free else ParkColors.Paid
}

@Composable
fun PlaceCard(
    place: ParkingPlace,
    status: FitStatus,
    settings: AppSettings,
    isFavorite: Boolean,
    distance: String?,
    onClose: () -> Unit,
    onNavigate: () -> Unit,
    onPark: () -> Unit,
    onFavorite: () -> Unit,
    onPay: (PayApp) -> Unit,
    extraLines: List<Pair<ImageVector, String>> = emptyList(),
    goLabel: String = "Navigate",
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = place.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val facts = listOfNotNull(
                        place.kind.label,
                        place.fee.label,
                        distance,
                        place.capacity?.let { "$it spaces" },
                    )
                    Text(
                        text = facts.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                        tint = if (isFavorite) ParkColors.Check else LocalContentColor.current,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            HeightBanner(place, status, settings)
            extraLines.forEach { (icon, text) -> InfoLine(icon, text) }
            place.openingHours?.let { InfoLine(Icons.Filled.Schedule, it) }
            place.operator?.let { InfoLine(Icons.Filled.Business, it) }
            if (place.access == "customers") InfoLine(Icons.Filled.Info, "Customers only")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNavigate, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Directions, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(goLabel)
                }
                OutlinedButton(onClick = onPark, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.LocalParking, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Park here")
                }
            }
            PayRow(onPay)
        }
    }
}

@Composable
fun HeightBanner(place: ParkingPlace, status: FitStatus, settings: AppSettings) {
    val tint = when (status) {
        FitStatus.TOO_LOW -> ParkColors.TooLow
        FitStatus.CHECK_SIGN -> ParkColors.Check
        else -> ParkColors.Free
    }
    val icon = if (status == FitStatus.TOO_LOW || status == FitStatus.CHECK_SIGN) Icons.Filled.Warning else Icons.Filled.CheckCircle
    Surface(color = tint.copy(alpha = 0.15f), shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint)
            Spacer(Modifier.width(8.dp))
            Text(
                text = Clearance.describe(place, status, settings),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
fun InfoLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PayRow(onPay: (PayApp) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Pay with", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PayApps.all.forEach { app ->
                AssistChip(
                    onClick = { onPay(app) },
                    label = { Text(app.label) },
                    leadingIcon = {
                        Icon(Icons.Filled.Payments, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize))
                    },
                )
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.size(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
