package com.moblized.parkpal.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moblized.parkpal.nav.NavSession
import com.moblized.parkpal.util.LocationHelper

@Composable
fun ParkPalRoot(vm: MainViewModel, dark: Boolean) {
    val ctx = LocalContext.current
    val tab by vm.tab.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val nav by NavSession.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var hasLocation by remember { mutableStateOf(LocationHelper.hasPermission(ctx)) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasLocation = LocationHelper.hasPermission(ctx)
    }
    LaunchedEffect(Unit) {
        val needed = buildList {
            if (!hasLocation) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        vm.messageShown()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { vm.setTab(t) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            when (tab) {
                AppTab.MAP -> MapScreen(vm, dark, hasLocation)
                AppTab.TIMELINE -> TimelineScreen(dark)
                AppTab.SPOT -> MySpotScreen(vm)
                AppTab.SAVED -> SavedScreen(vm)
                AppTab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }

    // Turn-by-turn navigation covers the app while it runs; the map underneath stays as you left it.
    nav?.let { state ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            NavigationScreen(state, dark, onParked = { vm.setTab(AppTab.SPOT) })
        }
    }

    val info = update.info
    if (info != null && update.showDialog) {
        UpdateDialog(
            info = info,
            state = update,
            currentBuild = vm.currentBuild,
            onUpdate = vm::startUpdate,
            onLater = { vm.dismissUpdate(skipThisBuild = false) },
            onSkip = { vm.dismissUpdate(skipThisBuild = true) },
        )
    }
}
