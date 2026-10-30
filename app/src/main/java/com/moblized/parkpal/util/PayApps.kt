package com.moblized.parkpal.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

data class PayApp(val label: String, val packageName: String)

/** Opens the parking payment apps. If one isn't installed, opens the Play Store to it instead. */
object PayApps {
    val all = listOf(
        PayApp("ParkMobile", "net.sharewire.parkmobilev2"),
        PayApp("SpotHero", "com.spothero.spothero"),
        PayApp("PayByPhone", "com.paybyphone"),
        PayApp("ParkWhiz", "com.parkwhiz.driverApp"),
    )

    fun byLabel(label: String): PayApp = all.firstOrNull { it.label == label } ?: all.first()

    fun isInstalled(context: Context, app: PayApp): Boolean =
        context.packageManager.getLaunchIntentForPackage(app.packageName) != null

    fun intentFor(context: Context, app: PayApp): Intent {
        val launch = context.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(app.label)}&c=apps"))
        return launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun open(context: Context, app: PayApp) {
        try {
            context.startActivity(intentFor(context, app))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/search?q=${Uri.encode(app.label)}&c=apps"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
