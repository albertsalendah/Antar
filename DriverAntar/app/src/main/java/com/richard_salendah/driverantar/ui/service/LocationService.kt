package com.richard_salendah.driverantar.ui.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.richard_salendah.driverantar.MainActivity
import com.richard_salendah.driverantar.R
import com.richard_salendah.driverantar.data.remote.DriverRepository
import com.richard_salendah.driverantar.data.remote.RetrofitClient
import com.richard_salendah.driverantar.utils.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * LocationService is the SINGLE source of GPS data for the entire app.
 *
 * Startup sequence:
 *   1. [syncImmediateLocation] — fires instantly when the driver goes Online.
 *      Calls getLastLocation() (device cache, instant) to push a location+isOnline
 *      update to the server right away. This ensures riders searching immediately
 *      after the driver goes online see an accurate nearby position, not stale data.
 *      Falls back to getCurrentLocation() if the cache is empty (e.g. cold start).
 *   2. [startPeriodicTracking] — requestLocationUpdates every 10s / min 5s.
 *      Keeps the position fresh while the driver is moving.
 *
 * Token safety:
 *   The JWT is captured in [capturedToken] at start time. goOffline() uses this
 *   token so it still works even after SessionManager.clear() is called on logout.
 */
class LocationService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository   = DriverRepository(RetrofitClient.instance)

    /** Token captured at start — safe to use even after SessionManager.clear() */
    private var capturedToken: String = ""

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SessionManager.init(applicationContext)

        capturedToken = SessionManager.token
        isRunning     = true

        createNotificationChannel()

        // Tapping the persistent foreground notification opens the app
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Driver Mode Online")
                .setContentText("Tracking your location for rides…")
                .setSmallIcon(R.drawable.ic_car)
                .setOngoing(true)
                .setContentIntent(openAppIntent)  // tap → open app
                .build()
        )

        if (hasLocationPermission()) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            // Step 1: push current position immediately so is_online + location
            //         are both up-to-date the moment the driver goes online.
            serviceScope.launch { syncImmediateLocation() }
            // Step 2: start periodic updates for ongoing tracking while moving.
            startPeriodicTracking()
        } else {
            Log.w(TAG, "Location permission not granted — stopping service")
            stopSelf()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false  // ← cleared before goOffline so MapViewModel sees false immediately

        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }

        // Mark driver offline synchronously before the service dies.
        // runBlocking is intentional — we cannot let the service die with
        // is_online=true in the DB.
        if (capturedToken.isNotBlank()) {
            runBlocking {
                try {
                    withTimeout(3_000) {
                        repository.goOffline(capturedToken)
                        Log.d(TAG, "Driver marked offline")
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "goOffline timed out — pg_cron will handle cleanup")
                } catch (e: Exception) {
                    Log.e(TAG, "goOffline failed", e)
                }
            }
        }

        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Immediate location sync ───────────────────────────────────────────────

    /**
     * Gets the best available location and syncs it to the server immediately.
     *
     * Strategy:
     *   1. Try getLastLocation() — returns the device's cached position instantly
     *      (no network/GPS warm-up needed). Good enough for "where is the driver now".
     *   2. If cache is null (device was just rebooted or GPS was off), fall back to
     *      getCurrentLocation() which forces a fresh GPS fix.
     *
     * This runs at service start so the first POST /location arrives within
     * milliseconds of the driver flipping the Online switch, not 10 seconds later.
     */
    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private suspend fun syncImmediateLocation() {
        if (!hasLocationPermission()) return

        try {
            // Attempt 1: use cached last-known location (instant)
            var location: Location? = fusedLocationClient.lastLocation.await()

            // Attempt 2: force a fresh fix if cache was empty
            if (location == null) {
                Log.d(TAG, "Last location null — requesting fresh fix")
                val req = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(0)   // don't accept stale cache
                    .setDurationMillis(5_000L)  // wait up to 5s for a fix
                    .build()
                location = fusedLocationClient.getCurrentLocation(req, null).await()
            }

            if (location != null) {
                Log.d(TAG, "Immediate sync: lat=${location.latitude} lng=${location.longitude}")
                // Emit to UI so marker moves instantly
                _locationFlow.emit(location)
                // Push to server — this also sets is_online=true in one SQL UPDATE
                repository.updateLocation(capturedToken, location.latitude, location.longitude)
            } else {
                Log.w(TAG, "Could not get any location for immediate sync")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Immediate location sync failed", e)
            // Non-fatal — periodic tracking will catch up on the next fix
        }
    }

    // ── Periodic GPS tracking ─────────────────────────────────────────────────

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startPeriodicTracking() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .build()

        if (hasLocationPermission()) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "Periodic GPS tracking started")
        }
    }

    // Only place in the app that receives periodic GPS fixes
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.locations.lastOrNull() ?: return
            serviceScope.launch { _locationFlow.emit(location) }
            syncWithServer(location)
        }
    }

    // ── Server sync (periodic) ────────────────────────────────────────────────

    private fun syncWithServer(location: Location) {
        serviceScope.launch {
            try {
                val success = repository.updateLocation(
                    token = capturedToken,
                    lat   = location.latitude,
                    lng   = location.longitude
                )
                if (success) {
                    Log.d(TAG, "Synced lat=${location.latitude} lng=${location.longitude}")
                } else {
                    Log.w(TAG, "Sync returned non-2xx — token expired or no active vehicle?")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Periodic sync failed", e)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hasLocationPermission() =
        ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Location Tracking", NotificationManager.IMPORTANCE_LOW)
        )
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG             = "LocationService"
        private const val CHANNEL_ID      = "location_channel"
        private const val NOTIFICATION_ID = 1

        private val _locationFlow = MutableSharedFlow<Location>(replay = 1)

        /** Read-only flow — collect in MapViewModel to move the map marker. */
        val locationFlow = _locationFlow.asSharedFlow()

        /**
         * True while the service is running (between onStartCommand and onDestroy).
         * MapViewModel reads this to detect stale is_online=true in the DB after
         * the app was killed while the driver was online.
         */
        var isRunning: Boolean = false
            private set
    }
}