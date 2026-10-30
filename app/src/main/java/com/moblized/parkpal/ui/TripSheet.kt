package com.moblized.parkpal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moblized.parkpal.data.Clearance
import com.moblized.parkpal.data.Destination
import com.moblized.parkpal.data.ParkingPlace
import com.moblized.parkpal.ui.theme.ParkColors
import com.moblized.parkpal.util.Format

/** Bottom card shown after you pick a destination: advice plus parking ranked by walk time. */
@Composable
fun TripSheet(
    dest: Destination,
    summary: TripSummary,
    items: List<TripItem>,
    radiusMeters: Int,
    expanded: Boolean,
    vehicleName: String,
    onToggle: () -> Unit,
    onSelect: (ParkingPlace) -> Unit,
    onDrive: () -> Unit,
    onFindParking: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Place, contentDescription = null, tint = ParkColors.Destination)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        dest.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (dest.subtitle.isNotBlank()) {
                        Text(
                            dest.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = if (expanded) "Hide list" else "Show list",
                    modifier = Modifier.padding(8.dp),
                )
            }

            Advice(summary, items.size, radiusMeters, vehicleName)

            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onDrive) {
                    Icon(Icons.Filled.Directions, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Drive there")
                }
                if (summary.advice == TripAdvice.NOT_SEARCHED) {
                    Button(onClick = onFindParking) {
                        Icon(Icons.Filled.LocalParking, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Find parking")
                    }
                }
            }

            if (expanded && items.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(end = 8.dp))
                LazyColumn(Modifier.heightIn(max = 250.dp)) {
                    itemsIndexed(items, key = { _, item -> item.place.id }) { index, item ->
                        val tag = when {
                            item == summary.onSite -> "On site"
                            index == 0 -> "Closest"
                            else -> null
                        }
                        TripRow(item, tag) { onSelect(item.place) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Advice(summary: TripSummary, count: Int, radiusMeters: Int, vehicleName: String) {
    val spots = if (count == 1) "1 spot" else "$count spots"
    when (summary.advice) {
        TripAdvice.NOT_SEARCHED -> AdviceLine(Icons.Filled.Info, MaterialTheme.colorScheme.primary, "Tap Find parking to see spots near here.")
        TripAdvice.SEARCHING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Finding parking that fits your $vehicleName…", style = MaterialTheme.typography.bodyMedium)
        }
        TripAdvice.WALKABLE -> {
            val walk = summary.youToDestMeters?.let { Format.walk(it) } ?: "a short walk"
            AdviceLine(
                Icons.Filled.DirectionsWalk,
                ParkColors.Free,
                "You're about $walk away, so you might not need to drive." +
                    if (count > 0) " $spots nearby if you do." else "",
            )
        }
        TripAdvice.ON_SITE -> {
            val onSite = summary.onSite
            val more = count - 1
            AdviceLine(
                Icons.Filled.CheckCircle,
                ParkColors.Free,
                "Has its own parking: ${onSite?.place?.name ?: "on site"}." +
                    if (more > 0) " $more more nearby." else "",
            )
        }
        TripAdvice.FOUND -> AdviceLine(
            Icons.Filled.LocalParking,
            MaterialTheme.colorScheme.primary,
            "$spots within ${Format.distance(radiusMeters.toDouble())}, closest first.",
        )
        TripAdvice.NONE -> AdviceLine(
            Icons.Filled.Info,
            ParkColors.Check,
            "No parking mapped within ${Format.distance(radiusMeters.toDouble())}. Street parking may be available, so check the signs.",
        )
    }
}

@Composable
private fun AdviceLine(icon: ImageVector, tint: Color, text: String) {
    Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TripRow(item: TripItem, tag: String?, onClick: () -> Unit) {
    val p = item.place
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(statusColor(p, item.status)),
            contentAlignment = Alignment.Center,
        ) {
            Text("P", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (tag != null) {
                    Spacer(Modifier.width(6.dp))
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(6.dp)) {
                        Text(
                            tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Text(
                listOf(Format.walk(item.walkMeters), p.kind.label, p.fee.label, Clearance.shortLabel(item.status))
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
    }
}
