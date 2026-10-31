package com.moblized.parkpal.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.moblized.parkpal.data.Poi
import com.moblized.parkpal.data.PoiCategory
import com.moblized.parkpal.util.Format
import com.moblized.parkpal.util.distanceMeters

/** Gas, Food, Coffee… buttons under the search bar, like Google Maps. */
@Composable
fun CategoryRow(active: PoiCategory?, onPick: (PoiCategory) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PoiCategory.entries.forEach { c ->
            val selected = c == active
            FilterChip(
                selected = selected,
                onClick = { onPick(c) },
                label = { Text("${c.emoji}  ${c.label}") },
                trailingIcon = if (selected) {
                    { Icon(Icons.Filled.Close, contentDescription = "Close ${c.label}", modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                } else {
                    null
                },
                colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                elevation = FilterChipDefaults.filterChipElevation(elevation = 2.dp),
            )
        }
    }
}

/** The results list for a category, under the map. */
@Composable
fun ExploreSheet(
    category: PoiCategory,
    pois: List<Poi>,
    loading: Boolean,
    from: Pair<Double, Double>?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (Poi) -> Unit,
    onSearchHere: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .animateContentSize(tween(220))
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 8.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryDot(category, 32)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${category.label} nearby",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            loading && pois.isEmpty() -> "Looking…"
                            pois.isEmpty() -> "Nothing found here"
                            pois.size == 1 -> "1 place"
                            else -> "${pois.size} places"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                TextButton(onClick = onSearchHere) {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("This area")
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = if (expanded) "Hide list" else "Show list",
                    modifier = Modifier.padding(8.dp),
                )
            }
            if (expanded && pois.isNotEmpty()) {
                Spacer(Modifier.size(4.dp))
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(pois.take(60), key = { it.id }) { poi ->
                        PoiRow(poi, from, onClick = { onSelect(poi) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun PoiRow(poi: Poi, from: Pair<Double, Double>?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(poi.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val bits = listOfNotNull(
                poi.detail,
                if (poi.diesel) "Diesel" else null,
                if (poi.trucks) "Trucks OK" else null,
                poi.address,
            )
            if (bits.isNotEmpty()) {
                Text(
                    bits.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (from != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                Format.distance(distanceMeters(from.first, from.second, poi.lat, poi.lon)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

@Composable
fun CategoryDot(category: PoiCategory, sizeDp: Int) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(Color(category.color)),
        contentAlignment = Alignment.Center,
    ) {
        Text(category.emoji, fontSize = (sizeDp * 0.5f).sp)
    }
}

/** Details for one gas station, restaurant… with directions, parking near it, and save to Dawarich. */
@Composable
fun PoiCard(
    poi: Poi,
    distance: String?,
    extraLines: List<Pair<ImageVector, String>>,
    saved: Boolean,
    canSave: Boolean,
    onClose: () -> Unit,
    onGo: () -> Unit,
    onParking: () -> Unit,
    onSave: () -> Unit,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                CategoryDot(poi.category, 36)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        poi.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(poi.detail ?: poi.category.fallbackName, distance).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (canSave) {
                    IconButton(onClick = onSave, enabled = !saved) {
                        Icon(
                            if (saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                            contentDescription = if (saved) "Saved in Dawarich" else "Save to Dawarich",
                            tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            if (poi.diesel || poi.trucks) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LocalShipping, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            listOfNotNull(
                                if (poi.diesel) "Has diesel" else null,
                                if (poi.trucks) "Trucks welcome" else null,
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            extraLines.forEach { (icon, text) -> InfoLine(icon, text) }
            poi.openingHours?.let { InfoLine(Icons.Filled.Schedule, it) }
            poi.address?.let { InfoLine(Icons.Filled.Place, it) }
            if (poi.source == "google") InfoLine(Icons.Filled.Info, "From Google Maps")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onGo, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Directions, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Go")
                }
                OutlinedButton(onClick = onParking, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.LocalParking, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Parking nearby", maxLines = 1)
                }
            }
            val phone = poi.phone
            val website = poi.website
            if (phone != null || website != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (phone != null) {
                        AssistChip(
                            onClick = { open(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.split(';').first().trim()))) },
                            label = { Text("Call") },
                            leadingIcon = { Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                        )
                    }
                    if (website != null) {
                        AssistChip(
                            onClick = {
                                val url = if (website.startsWith("http", ignoreCase = true)) website else "https://$website"
                                open(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            },
                            label = { Text("Website") },
                            leadingIcon = { Icon(Icons.Filled.Public, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                        )
                    }
                }
            }
        }
    }
}

private fun open(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}
