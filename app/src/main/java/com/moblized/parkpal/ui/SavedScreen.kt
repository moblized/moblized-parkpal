package com.moblized.parkpal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moblized.parkpal.data.AppSettings
import com.moblized.parkpal.data.Clearance
import com.moblized.parkpal.data.Destination
import com.moblized.parkpal.data.ParkingPlace
import com.moblized.parkpal.data.ParkingSession
import com.moblized.parkpal.ui.theme.ParkColors
import com.moblized.parkpal.util.Format

@Composable
fun SavedScreen(vm: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val places by vm.dawarichPlaces.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.onSearchFocused() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            "Saved",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
        )
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Places") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Garages (${favorites.size})") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("History") })
        }
        when (tab) {
            0 -> PlacesList(places, settings, vm)
            1 -> FavoritesList(favorites, settings, vm)
            else -> HistoryList(history, vm)
        }
    }
}

/** Your Dawarich places: tap one to see parking around it, or drive straight there. */
@Composable
private fun PlacesList(places: List<Destination>, settings: AppSettings, vm: MainViewModel) {
    val connected = settings.dawarichUrl.isNotBlank() && settings.dawarichKey.isNotBlank()
    if (places.isEmpty()) {
        EmptyState(
            Icons.Filled.Bookmark,
            if (connected) "No places yet" else "Your places live in Dawarich",
            if (connected) {
                "Places you add in Dawarich show up here. You can also save gas stations, restaurants and more from Explore with the bookmark button."
            } else {
                "Connect Dawarich in Settings to see your places here and use them as destinations."
            },
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(places, key = { "${it.title}|${it.lat}|${it.lon}" }) { d ->
            ListItem(
                headlineContent = { Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = if (d.subtitle.isNotBlank()) {
                    { Text(d.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                } else {
                    null
                },
                leadingContent = {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                },
                trailingContent = {
                    IconButton(onClick = { vm.driveToSaved(d) }) {
                        Icon(Icons.Filled.Directions, contentDescription = "Directions")
                    }
                },
                modifier = Modifier.clickable { vm.pickDestination(d) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun FavoritesList(favorites: List<ParkingPlace>, settings: AppSettings, vm: MainViewModel) {
    val ctx = LocalContext.current
    if (favorites.isEmpty()) {
        EmptyState(Icons.Filled.StarBorder, "No favorites yet", "Tap the star on any garage or lot to keep it here.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(favorites, key = { it.id }) { p ->
            val status = Clearance.fitStatus(p, settings)
            ListItem(
                headlineContent = { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${p.kind.label} · ${p.fee.label} · ${Clearance.shortLabel(status)}") },
                leadingContent = {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape).background(statusColor(p, status)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("P", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                trailingContent = {
                    Row {
                        IconButton(onClick = { vm.driveToPlace(p) }) {
                            Icon(Icons.Filled.Directions, contentDescription = "Navigate")
                        }
                        IconButton(onClick = { vm.toggleFavorite(p) }) {
                            Icon(Icons.Filled.Star, contentDescription = "Remove from favorites", tint = ParkColors.Check)
                        }
                    }
                },
                modifier = Modifier.clickable { vm.showPlace(p) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun HistoryList(history: List<ParkingSession>, vm: MainViewModel) {
    var confirmClear by remember { mutableStateOf(false) }
    if (history.isEmpty()) {
        EmptyState(Icons.Filled.History, "No parking history", "Spots you end will show up here.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextButton(onClick = { confirmClear = true }) { Text("Clear history") }
            }
        }
        items(history, key = { it.id }) { s ->
            val duration = s.endedAt?.let { Format.duration(it - s.startedAt) }
            val line = listOfNotNull(Format.dateTime(s.startedAt), duration, s.level.ifBlank { null }).joinToString(" · ")
            ListItem(
                headlineContent = { Text(s.title(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(line, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    IconButton(onClick = { vm.deleteHistory(s.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                },
                modifier = Modifier.clickable { vm.showOnMap(s.lat, s.lon) },
            )
            HorizontalDivider()
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear parking history?") },
            text = { Text("This removes all past spots and their photos.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearHistory()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
