package com.moblized.parkpal.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

object LocationHelper {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The last known position if it's recent, otherwise a fresh fix. Faster than [current]. */
    @SuppressLint("MissingPermission")
    suspend fun quick(context: Context): Location? {
        if (!hasPermission(context)) return null
        val last: Location? = try {
            LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (last != null && System.currentTimeMillis() - last.time < 2 * 60_000L) return last
        return current(context) ?: last
    }

    /** A fresh GPS fix, falling back to the last known position. */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location? {
        if (!hasPermission(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val fresh: Location? = try {
            withTimeoutOrNull(15_000) {
                client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token).await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (fresh != null) return fresh
        return try {
            client.lastLocation.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
