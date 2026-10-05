package com.solartracker.pro.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.solartracker.pro.core.solar.GeoLocation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Source of the device location; abstracted so the ViewModel can be unit tested. */
interface LocationProvider {
    fun hasPermission(): Boolean
    fun isLocationEnabled(): Boolean
    suspend fun currentLocation(timeoutMillis: Long = 20_000): GeoLocation?
}

/**
 * One-shot device location using the platform [LocationManager] (no Google Play Services).
 * GPS is optional: every calculation works with a manually entered location.
 */
class LocationRepository(context: Context) : LocationProvider {

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun isLocationEnabled(): Boolean =
        locationManager != null && enabledProviders().isNotEmpty()

    /** Returns the current location, a recent cached one, or null if unavailable. */
    override suspend fun currentLocation(timeoutMillis: Long): GeoLocation? {
        val manager = locationManager ?: return null
        if (!hasPermission()) return null
        val providers = enabledProviders()
        if (providers.isEmpty()) return null

        val fresh = withTimeoutOrNull(timeoutMillis) { requestSingleFix(manager, providers.first()) }
        val location = fresh ?: lastKnownLocation(manager, providers)
        return location?.let {
            val altitude = if (it.hasAltitude()) it.altitude.takeIf { a -> a.isFinite() }?.coerceIn(-500.0, 9000.0) else null
            GeoLocation(it.latitude.coerceIn(-90.0, 90.0), it.longitude.coerceIn(-180.0, 180.0), altitude ?: 0.0)
        }
    }

    private fun enabledProviders(): List<String> {
        val manager = locationManager ?: return emptyList()
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    }

    @SuppressLint("MissingPermission") // checked by hasPermission()
    private fun lastKnownLocation(manager: LocationManager, providers: List<String>): Location? {
        if (!hasPermission()) return null
        return try {
            providers.mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    @SuppressLint("MissingPermission") // checked by hasPermission()
    private suspend fun requestSingleFix(manager: LocationManager, provider: String): Location? {
        if (!hasPermission()) return null
        return suspendCancellableCoroutine { cont ->
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, appContext.mainExecutor) { location ->
                        if (cont.isActive) cont.resume(location)
                    }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            manager.removeUpdates(this)
                            if (cont.isActive) cont.resume(location)
                        }

                        // Abstract before API 30 – must be implemented to avoid AbstractMethodError.
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                        override fun onProviderEnabled(provider: String) = Unit
                        override fun onProviderDisabled(provider: String) = Unit
                    }
                    cont.invokeOnCancellation { manager.removeUpdates(listener) }
                    manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                }
            } catch (e: SecurityException) {
                if (cont.isActive) cont.resume(null)
            } catch (e: IllegalArgumentException) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
