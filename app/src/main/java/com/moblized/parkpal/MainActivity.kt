package com.moblized.parkpal

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moblized.parkpal.data.SettingsStore
import com.moblized.parkpal.data.ThemeMode
import com.moblized.parkpal.icon.IconSwitcher
import com.moblized.parkpal.record.LocationRecorderService
import com.moblized.parkpal.ui.AppTab
import com.moblized.parkpal.ui.MainViewModel
import com.moblized.parkpal.ui.ParkPalRoot
import com.moblized.parkpal.ui.theme.ParkPalTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIntent(intent)
            vm.checkForUpdate(manual = false)
        }
        setContent {
            val settings by SettingsStore.settings.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK, ThemeMode.BLACK -> true
            }
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            ParkPalTheme(dark = dark, black = settings.themeMode == ThemeMode.BLACK) {
                ParkPalRoot(vm = vm, dark = dark)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Keep recording your timeline for Dawarich if it's turned on (e.g. after a restart).
        LocationRecorderService.startIfEnabled(this)
    }

    override fun onResume() {
        super.onResume()
        // Finish an update install if you just allowed ParkPal to install apps.
        vm.onAppResumed()
    }

    override fun onStop() {
        super.onStop()
        // Swap the launcher icon while the app is in the background, so it never closes the app mid-use.
        IconSwitcher.apply(this, SettingsStore.settings.value.themeMode)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_PARK_HERE -> vm.parkHere()
            ACTION_OPEN_SPOT -> vm.setTab(AppTab.SPOT)
            ACTION_WALK_TO_CAR -> vm.walkToCar()
        }
    }

    companion object {
        const val ACTION_PARK_HERE = "com.moblized.parkpal.PARK_HERE"
        const val ACTION_OPEN_SPOT = "com.moblized.parkpal.OPEN_SPOT"
        const val ACTION_WALK_TO_CAR = "com.moblized.parkpal.WALK_TO_CAR"
    }
}
