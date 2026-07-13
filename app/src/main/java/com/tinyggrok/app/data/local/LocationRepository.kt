package com.tinyggrok.app.data.local

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest as PlatformLocationRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** High-accuracy user location for chat context (coords sent to xAI via prompt only). */
data class UserLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val locality: String? = null,
    val adminArea: String? = null,
    val countryCode: String? = null,
    val postalCode: String? = null,
    /** Full reverse-geocode line when available (more reliable than locality alone in the UK). */
    val addressLine: String? = null,
    val ageMs: Long = 0L,
    val provider: String? = null
) {
    /**
     * Compact string for system instructions.
     * Coordinates are authoritative; reverse-geocoded place names are only included from
     * a precise GPS fix (never from coarse cell/network).
     */
    fun toInstructionSnippet(): String {
        val coords = String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
        val accuracy = accuracyMeters?.let { " (±${it.roundToInt()} m)" }.orEmpty()
        val providerNote = provider?.let { " via $it" }.orEmpty()
        val ageNote = when {
            ageMs < 5_000L -> " just now"
            ageMs < 60_000L -> " ${ageMs / 1000}s ago"
            else -> " ${ageMs / 60_000}m ago"
        }

        val place = listOfNotNull(locality, adminArea, countryCode)
            .joinToString(", ")
            .ifBlank { null }
        val postcode = postalCode?.takeIf { it.isNotBlank() }
        val line = addressLine?.takeIf { it.isNotBlank() }

        val accuracyOkForPlace =
            accuracyMeters != null &&
                accuracyMeters <= MAX_PLACE_NAME_ACCURACY_M &&
                isGpsProviderName(provider)

        return buildString {
            append(
                "User GPS coordinates (authoritative, high-precision): " +
                    "$coords$accuracy$providerNote, measured$ageNote."
            )
            if (accuracyOkForPlace && (place != null || postcode != null || line != null)) {
                append(" Resolved place (reverse-geocoded from this GPS fix only):")
                if (place != null) append(" $place")
                if (postcode != null) append(" (postcode $postcode)")
                if (line != null && line != place) append(". Address: $line")
                append('.')
                append(
                    " Prefer the coordinates (and postcode when present) over the place name " +
                        "if they disagree."
                )
            } else {
                append(
                    " No reliable town name is attached (requiring a precise GPS fix). " +
                        "Resolve nearest stations and places from the coordinates alone — " +
                        "do not invent a town or district name from a coarse location."
                )
            }
        }
    }

    companion object {
        /** Only name a town when GPS reports at least this accuracy. */
        const val MAX_PLACE_NAME_ACCURACY_M = 50f

        fun isGpsProviderName(provider: String?): Boolean {
            if (provider.isNullOrBlank()) return false
            val p = provider.lowercase(Locale.US)
            return p == LocationManager.GPS_PROVIDER ||
                p.contains("gps") ||
                p.contains("gnss")
        }
    }
}

/**
 * High-accuracy location via platform [LocationManager] **GPS only** (no Play Services).
 *
 * Battery-friendly strategy — **cache with timeout**, not continuous tracking:
 * 1. Optional one-shot warm lookup when the app comes to foreground (fills the cache).
 * 2. Cache is valid for the user-configured TTL (Settings → GPS cache timeout, default
 *    10 minutes); reads within that window do not touch GPS.
 * 3. On send (or when TTL expired), take a single fresh GPS session, then stop the chip.
 *
 * Continuous refresh would drain battery; a TTL cache is enough for "from my current
 * location" while the user is typing, and forces a re-fix after the timeout.
 */
@Singleton
class LocationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    private val cacheMutex = Mutex()
    /** Last good GPS sample; age is derived from [cachedAtElapsedRealtimeMs]. */
    private var cachedGps: UserLocation? = null
    private var cachedAtElapsedRealtimeMs: Long = 0L

    private val warmupLock = Any()
    private var warmupJob: Job? = null
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * One-shot background GPS to fill the cache when the app becomes visible.
     *
     * Why: cold GNSS locks are slow (often 10–30s). Starting a single lookup at app
     * open means “from my current location to X” can often use a cached fix at send
     * time without blocking as long.
     *
     * Why not continuous refresh: holding the GPS chip open drains battery. We take
     * one (short) session, store the best sample, then release GNSS. Later reads use
     * that cache until the configured TTL expires, then we look up again.
     *
     * Safe to call repeatedly; no-ops if a warm-up is already running or permission/GPS
     * is missing. Does nothing useful if the cache is already fresh.
     */
    fun startGpsWarmup() {
        if (!hasFineLocationPermission()) return
        val lm = locationManager() ?: return
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return

        synchronized(warmupLock) {
            if (warmupJob?.isActive == true) return
            warmupJob = warmupScope.launch {
                // Skip the chip entirely if we still have a valid cached fix.
                val ttlMs = cacheTtlMs()
                val stillFresh = readCachedGps(ttlMs, MAX_CACHE_GPS_ACCURACY_M) != null
                if (stillFresh) return@launch

                runCatching {
                    val fix = requestGpsSession(
                        lm = lm,
                        timeoutMs = WARMUP_TIMEOUT_MS,
                        earlyExitAccuracyM = EARLY_EXIT_ACCURACY_M,
                        minSamplesForEarlyExit = 2
                    )
                    if (fix != null) writeCache(fix)
                }
            }
        }
    }

    /**
     * Cancel an in-flight warm-up (e.g. app backgrounded). Does not clear the cache —
     * a still-valid TTL entry remains usable until it expires.
     */
    fun stopGpsWarmup() {
        synchronized(warmupLock) {
            warmupJob?.cancel()
            warmupJob = null
        }
    }

    /**
     * Best-effort location for chat: return cached GPS if within TTL, otherwise one
     * fresh GPS session (then cache + stop). Never reverse-geocodes coarse network fixes.
     *
     * @param maxAgeMs optional override; when null, uses the Settings GPS cache timeout.
     */
    suspend fun getApproximateLocation(
        maxAgeMs: Long? = null,
        waitTimeoutMs: Long = GPS_WAIT_TIMEOUT_MS
    ): UserLocation? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) return@withContext null

        val ttlMs = maxAgeMs ?: cacheTtlMs()

        // 1) Cache hit within timeout — no GPS, no battery cost.
        readCachedGps(ttlMs, MAX_TRUSTED_GPS_ACCURACY_M)?.let { cached ->
            return@withContext finalizeLocation(cached)
        }

        val lm = locationManager()
        val gpsReady = lm != null &&
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            hasFineLocationPermission()

        // 2) Cache miss / expired — single GPS session, then release the chip.
        if (gpsReady) {
            val gpsFix = requestGpsSession(
                lm = lm!!,
                timeoutMs = waitTimeoutMs,
                earlyExitAccuracyM = EARLY_EXIT_ACCURACY_M,
                minSamplesForEarlyExit = 2
            )
            if (gpsFix != null) {
                writeCache(gpsFix)
                return@withContext finalizeLocation(gpsFix)
            }

            // Session failed mid-way; accept a slightly looser cache entry if present.
            readCachedGps(ttlMs, MAX_CACHE_GPS_ACCURACY_M)?.let { recent ->
                return@withContext finalizeLocation(recent)
            }
        }

        // 3) OS last-known GPS (still never network for a named place).
        lastKnownGps(lm)?.let { last ->
            if (last.ageMs <= ttlMs &&
                (last.accuracyMeters ?: Float.MAX_VALUE) <= MAX_LAST_KNOWN_GPS_ACCURACY_M
            ) {
                writeCache(last)
                return@withContext finalizeLocation(last)
            }
        }

        // 4) Network coordinates only — no reverse-geocoded town.
        lastKnownNetwork(lm)?.let { network ->
            return@withContext network.copy(
                locality = null,
                adminArea = null,
                postalCode = null,
                addressLine = null
            )
        }
        null
    }

    /**
     * One GPS session: stream high-accuracy updates until timeout or good enough accuracy,
     * then tear down listeners (chip can sleep again).
     */
    @SuppressLint("MissingPermission")
    private suspend fun requestGpsSession(
        lm: LocationManager,
        timeoutMs: Long,
        earlyExitAccuracyM: Float,
        minSamplesForEarlyExit: Int
    ): UserLocation? {
        val best = AtomicReference<UserLocation?>(null)
        val sampleCount = AtomicInteger(0)

        fun consider(loc: UserLocation) {
            while (true) {
                val cur = best.get()
                val winner = when {
                    cur == null -> loc
                    gpsScore(loc) < gpsScore(cur) -> loc
                    else -> cur
                }
                if (winner === cur) break
                if (best.compareAndSet(cur, winner)) break
            }
        }

        try {
            withTimeoutOrNull(timeoutMs) {
                gpsUpdatesFlow(lm).collect { fix ->
                    val labelled = fix.copy(
                        provider = fix.provider?.takeIf { it.isNotBlank() }
                            ?: LocationManager.GPS_PROVIDER
                    )
                    sampleCount.incrementAndGet()
                    consider(labelled)
                    val acc = best.get()?.accuracyMeters
                    if (sampleCount.get() >= minSamplesForEarlyExit &&
                        acc != null &&
                        acc <= earlyExitAccuracyM
                    ) {
                        throw AccuracyReached()
                    }
                }
            }
        } catch (_: AccuracyReached) {
            // Good enough — exit early and release GPS in awaitClose.
        }

        return best.get()
    }

    /** High-accuracy GPS update stream; cancelled/closed when the collector stops. */
    @SuppressLint("MissingPermission")
    private fun gpsUpdatesFlow(lm: LocationManager) = callbackFlow {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val user = location.toUserLocation(System.currentTimeMillis()).let {
                    if (it.provider.isNullOrBlank()) {
                        it.copy(provider = LocationManager.GPS_PROVIDER)
                    } else {
                        it
                    }
                }
                trySend(user)
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        val thread = HandlerThread("tiny-ggrok-gps").apply { start() }
        val looper = thread.looper
        val executor = Executor { command -> Handler(looper).post(command) }

        var registered = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val request = PlatformLocationRequest.Builder(/* intervalMillis */ 0L)
                    .setQuality(PlatformLocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(0L)
                    .setMinUpdateDistanceMeters(0f)
                    .build()
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    request,
                    executor,
                    listener
                )
                registered = true
            }
        } catch (_: Exception) {
            registered = false
        }

        if (!registered) {
            try {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    0L,
                    0f,
                    listener,
                    looper
                )
                registered = true
            } catch (_: SecurityException) {
                close()
            } catch (_: Exception) {
                close()
            }
        }

        awaitClose {
            try {
                lm.removeUpdates(listener)
            } catch (_: Exception) {
            }
            thread.quitSafely()
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownGps(lm: LocationManager?): UserLocation? {
        if (lm == null) return null
        val now = System.currentTimeMillis()
        return try {
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) null
            else lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?.toUserLocation(now)
                ?.copy(provider = LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownNetwork(lm: LocationManager?): UserLocation? {
        if (lm == null) return null
        val now = System.currentTimeMillis()
        return try {
            if (!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) null
            else lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?.toUserLocation(now)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Return the cache if younger than [maxAgeMs] and accurate enough.
     * Age is wall time since [writeCache], so TTL actually expires.
     */
    private suspend fun readCachedGps(
        maxAgeMs: Long,
        maxAccuracyM: Float
    ): UserLocation? = cacheMutex.withLock {
        val c = cachedGps ?: return@withLock null
        if (!UserLocation.isGpsProviderName(c.provider)) return@withLock null
        val ageMs = (SystemClock.elapsedRealtime() - cachedAtElapsedRealtimeMs)
            .coerceAtLeast(0L)
        if (ageMs > maxAgeMs) return@withLock null
        if ((c.accuracyMeters ?: Float.MAX_VALUE) > maxAccuracyM) return@withLock null
        c.copy(ageMs = ageMs)
    }

    private suspend fun writeCache(fix: UserLocation) {
        val acc = fix.accuracyMeters ?: return
        if (acc > MAX_CACHE_GPS_ACCURACY_M) return
        if (!UserLocation.isGpsProviderName(fix.provider) &&
            fix.provider != null &&
            fix.provider != LocationManager.GPS_PROVIDER
        ) {
            // Allow GPS-provider path samples that omit a provider string.
        }
        val labelled = fix.copy(
            provider = fix.provider?.takeIf { it.isNotBlank() }
                ?: LocationManager.GPS_PROVIDER
        )
        cacheMutex.withLock {
            cachedGps = labelled
            cachedAtElapsedRealtimeMs = SystemClock.elapsedRealtime()
        }
    }

    /** Configured cache TTL from Settings (default 10 minutes). */
    private suspend fun cacheTtlMs(): Long {
        val minutes = settingsRepository.locationCacheTimeoutMinutes.first()
        return minutes.toLong() * 60_000L
    }

    private fun locationManager(): LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private fun finalizeLocation(location: UserLocation): UserLocation {
        val acc = location.accuracyMeters
        val isGps = UserLocation.isGpsProviderName(location.provider)
        if (!isGps || acc == null || acc > UserLocation.MAX_PLACE_NAME_ACCURACY_M) {
            return location.copy(
                locality = null,
                adminArea = null,
                postalCode = null,
                addressLine = null
            )
        }
        return reverseGeocode(location)
    }

    private fun Location.toUserLocation(nowMs: Long): UserLocation {
        val age = if (elapsedRealtimeNanos > 0L) {
            val elapsedMs =
                (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000L
            elapsedMs.coerceAtLeast(0L)
        } else {
            (nowMs - time).coerceAtLeast(0L)
        }
        return UserLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = if (hasAccuracy()) accuracy else null,
            ageMs = age,
            provider = provider
        )
    }

    private fun gpsScore(loc: UserLocation): Double {
        val accuracy = (loc.accuracyMeters ?: DEFAULT_UNKNOWN_ACCURACY_M).toDouble()
        val ageSec = loc.ageMs / 1000.0
        return accuracy + ageSec * 0.25
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocode(location: UserLocation): UserLocation {
        if (!Geocoder.isPresent()) return location
        return try {
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 8)
                .orEmpty()
            if (addresses.isEmpty()) return location

            val town = resolveTownName(addresses)
            val bestForMeta = pickBestAddress(addresses)
            val line = bestForMeta.getAddressLine(0)?.trim()?.takeIf { it.isNotBlank() }
            val postcode = addresses
                .mapNotNull { it.postalCode?.takeIf { pc -> pc.isNotBlank() } }
                .maxByOrNull { it.length }
                ?: bestForMeta.postalCode

            location.copy(
                locality = town,
                adminArea = bestForMeta.adminArea ?: location.adminArea,
                countryCode = bestForMeta.countryCode ?: location.countryCode,
                postalCode = postcode ?: location.postalCode,
                addressLine = line
            )
        } catch (_: Exception) {
            location
        }
    }

    private fun pickBestAddress(addresses: List<Address>): Address {
        val withLocalityAndPostcode = addresses.firstOrNull {
            !it.locality.isNullOrBlank() && !it.postalCode.isNullOrBlank()
        }
        if (withLocalityAndPostcode != null) return withLocalityAndPostcode
        val withLocality = addresses.firstOrNull { !it.locality.isNullOrBlank() }
        if (withLocality != null) return withLocality
        val withSubLocality = addresses.firstOrNull { !it.subLocality.isNullOrBlank() }
        if (withSubLocality != null) return withSubLocality
        return addresses.first()
    }

    private fun resolveTownName(all: List<Address>): String? {
        val localities = all.mapNotNull { it.locality?.trim()?.takeIf { n -> n.isNotBlank() } }
        if (localities.isNotEmpty()) {
            return localities.groupingBy { it.lowercase(Locale.UK) }
                .eachCount()
                .maxByOrNull { it.value }
                ?.let { (key, _) -> localities.first { it.equals(key, ignoreCase = true) } }
        }

        val subLocalities =
            all.mapNotNull { it.subLocality?.trim()?.takeIf { n -> n.isNotBlank() } }
        if (subLocalities.isNotEmpty()) {
            return subLocalities.groupingBy { it.lowercase(Locale.UK) }
                .eachCount()
                .maxByOrNull { it.value }
                ?.let { (key, _) -> subLocalities.first { it.equals(key, ignoreCase = true) } }
        }

        val primary = all.first()
        val line = primary.getAddressLine(0)?.substringBefore(',')?.trim()
        if (!line.isNullOrBlank() && !line.first().isDigit()) return line
        return null
    }

    private class AccuracyReached : Exception()

    companion object {
        /** Max wait for a live GPS session when the cache is cold/expired. */
        private const val GPS_WAIT_TIMEOUT_MS = 25_000L

        /** Max wait for the optional app-start warm fill of the cache. */
        private const val WARMUP_TIMEOUT_MS = 30_000L

        /** Stop a GPS session early once accuracy is this good (metres). */
        private const val EARLY_EXIT_ACCURACY_M = 20f

        /** Prefer cache only when at least this accurate. */
        private const val MAX_TRUSTED_GPS_ACCURACY_M = 30f

        /** Store samples up to this accuracy. */
        private const val MAX_CACHE_GPS_ACCURACY_M = 100f

        /** OS last-known GPS accuracy ceiling. */
        private const val MAX_LAST_KNOWN_GPS_ACCURACY_M = 75f

        private const val DEFAULT_UNKNOWN_ACCURACY_M = 2000f
    }
}
