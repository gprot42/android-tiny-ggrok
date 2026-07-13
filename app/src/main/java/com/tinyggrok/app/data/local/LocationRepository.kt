package com.tinyggrok.app.data.local

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Approximate user location for chat context (never sent to third parties except xAI via prompt). */
data class UserLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val locality: String? = null,
    val adminArea: String? = null,
    val countryCode: String? = null,
    val ageMs: Long = 0L
) {
    /** Compact string for system instructions. */
    fun toInstructionSnippet(): String {
        val place = listOfNotNull(locality, adminArea, countryCode)
            .joinToString(", ")
            .ifBlank { null }
        val coords = String.format(Locale.US, "%.4f, %.4f", latitude, longitude)
        val accuracy = accuracyMeters?.let { " (±${it.toInt()} m)" }.orEmpty()
        return if (place != null) {
            "Approximate user location: $place ($coords)$accuracy"
        } else {
            "Approximate user location: $coords$accuracy"
        }
    }
}

@Singleton
class LocationRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    /**
     * Best-effort current location: prefers a recent last-known fix, otherwise waits briefly
     * for a single update. Returns null if permission is missing or no fix is available.
     */
    suspend fun getApproximateLocation(
        maxAgeMs: Long = 10 * 60 * 1000L,
        waitTimeoutMs: Long = 4_000L
    ): UserLocation? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) return@withContext null

        val last = getLastKnownLocation()
        if (last != null && last.ageMs <= maxAgeMs) {
            return@withContext reverseGeocode(last)
        }

        val fresh = withTimeoutOrNull(waitTimeoutMs) { requestSingleUpdate() }
        val best = listOfNotNull(fresh, last).minByOrNull { it.ageMs } ?: return@withContext null
        reverseGeocode(best)
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(): UserLocation? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        val now = System.currentTimeMillis()
        return providers
            .mapNotNull { provider ->
                try {
                    if (!lm.isProviderEnabled(provider)) null
                    else lm.getLastKnownLocation(provider)
                } catch (_: SecurityException) {
                    null
                } catch (_: Exception) {
                    null
                }
            }
            .maxByOrNull { it.time }
            ?.toUserLocation(now)
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleUpdate(): UserLocation? =
        suspendCancellableCoroutine { cont ->
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            val provider = when {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                    LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                    LocationManager.GPS_PROVIDER
                else -> {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
            }

            val resumed = AtomicBoolean(false)
            val listener = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (resumed.compareAndSet(false, true)) {
                        try {
                            lm.removeUpdates(this)
                        } catch (_: Exception) {
                        }
                        cont.resume(location.toUserLocation(System.currentTimeMillis()))
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            cont.invokeOnCancellation {
                try {
                    lm.removeUpdates(listener)
                } catch (_: Exception) {
                }
            }

            try {
                lm.requestLocationUpdates(
                    provider,
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper()
                )
            } catch (_: SecurityException) {
                if (resumed.compareAndSet(false, true)) cont.resume(null)
            } catch (_: Exception) {
                if (resumed.compareAndSet(false, true)) cont.resume(null)
            }
        }

    private fun Location.toUserLocation(nowMs: Long): UserLocation =
        UserLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            ageMs = (nowMs - time).coerceAtLeast(0L)
        )

    @Suppress("DEPRECATION")
    private fun reverseGeocode(location: UserLocation): UserLocation {
        if (!Geocoder.isPresent()) return location
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            // Classic sync API works on minSdk 24; may return empty on some devices/ROMs.
            val addr = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?: return location
            location.copy(
                locality = addr.locality ?: addr.subAdminArea ?: addr.subLocality,
                adminArea = addr.adminArea,
                countryCode = addr.countryCode
            )
        } catch (_: Exception) {
            location
        }
    }
}
