package com.moblized.parkpal.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.moblized.parkpal.data.OverpassApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val build: Int,
    val notes: String,
    val apkUrl: String,
    val pageUrl: String,
)

/** Checks the GitHub releases of moblized/moblized-parkpal for a newer build and installs it. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/moblized/moblized-parkpal/releases/latest"
    private const val PREFS = "parkpal_update"
    private const val KEY_SKIPPED = "skippedBuild"

    @Suppress("DEPRECATION")
    fun currentBuild(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    /** Returns the latest release if it is newer than the installed build, otherwise null. */
    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", OverpassApi.USER_AGENT)
            val code = conn.responseCode
            if (code == 404) return@withContext null
            if (code !in 200..299) throw IOException("GitHub returned HTTP $code")
            val release = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = release.optString("tag_name").substringAfterLast('-').toIntOrNull()
                ?: return@withContext null
            if (build <= currentBuild(context)) {
                clearDownloads(context)
                return@withContext null
            }
            val assets = release.optJSONArray("assets") ?: return@withContext null
            val apk = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?: return@withContext null
            UpdateInfo(
                build = build,
                notes = release.optString("body").trim(),
                apkUrl = apk.getString("browser_download_url"),
                pageUrl = release.optString("html_url"),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the update APK, reporting progress from 0 to 1. */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            clearDownloads(context)
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val part = File(dir, "ParkPal-${info.build}.apk.part")
            val target = File(dir, "ParkPal-${info.build}.apk")
            val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
            try {
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", OverpassApi.USER_AGENT)
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                var done = 0L
                var lastPercent = -1
                conn.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent / 100f)
                                }
                            }
                        }
                    }
                }
                if (!part.renameTo(target)) throw IOException("Couldn't save the update")
                // Make sure what we downloaded really is ParkPal before offering to install it.
                @Suppress("DEPRECATION")
                val pkg = context.packageManager.getPackageArchiveInfo(target.absolutePath, 0)?.packageName
                if (pkg != context.packageName) {
                    target.delete()
                    throw IOException("The downloaded file isn't a ParkPal update")
                }
                target
            } finally {
                conn.disconnect()
                part.delete()
            }
        }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where you allow ParkPal to install updates. */
    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun isSkipped(context: Context, build: Int): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_SKIPPED, -1) == build

    fun skip(context: Context, build: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_SKIPPED, build).apply()
    }

    private fun clearDownloads(context: Context) {
        File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
    }
}
