package com.moblized.parkpal.icon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.moblized.parkpal.data.ThemeMode

/** Swaps the home-screen icon to the dark version whenever the app is in dark mode. */
object IconSwitcher {
    private const val LIGHT = "com.moblized.parkpal.LauncherLight"
    private const val DARK = "com.moblized.parkpal.LauncherDark"

    fun apply(context: Context, mode: ThemeMode) {
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val dark = when (mode) {
            ThemeMode.SYSTEM -> systemDark
            ThemeMode.LIGHT -> false
            ThemeMode.DARK, ThemeMode.BLACK -> true
        }
        val pm = context.packageManager
        val light = ComponentName(context.packageName, LIGHT)
        val darkAlias = ComponentName(context.packageName, DARK)
        val on = if (dark) darkAlias else light
        val off = if (dark) light else darkAlias
        val alreadySet = pm.getComponentEnabledSetting(on) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED &&
            pm.getComponentEnabledSetting(off) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        // Light is the manifest default, so nothing to do on a light install that has never switched.
        val untouchedLight = !dark &&
            pm.getComponentEnabledSetting(light) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT &&
            pm.getComponentEnabledSetting(darkAlias) == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        if (alreadySet || untouchedLight) return
        pm.setComponentEnabledSetting(on, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        pm.setComponentEnabledSetting(off, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
}
