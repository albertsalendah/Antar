package com.richard_salendah.driverantar.ui.map

import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.driverantar.data.remote.DriverRepository
import com.richard_salendah.driverantar.ui.navigation.Screen
import com.richard_salendah.driverantar.ui.service.LocationService
import com.richard_salendah.driverantar.utils.SessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

class MapViewModel(private val repository: DriverRepository) : ViewModel() {

    var currentGeoPoint     by mutableStateOf(GeoPoint(0.0, 0.0)); private set
    var isOnline            by mutableStateOf(false);               private set
    var activeVehicleId     by mutableStateOf<String?>(null);       private set
    var showNoVehicleDialog by mutableStateOf(false);               private set
    var avatarUrl           by mutableStateOf<String?>(null);       private set
    var permissionGranted   by mutableStateOf(true);                private set
    var gpsEnabled          by mutableStateOf(true);                private set
    var recoveredTripRoute  by mutableStateOf<String?>(null);       private set

    // ── Rating — shown in top bar ─────────────────────────────────────────────
    var avgRating   by mutableStateOf<Double?>(null); private set
    var ratingCount by mutableStateOf(0);             private set

    // ── Incoming trip count — drives the badge on the "Lihat Perjalanan" FAB ──
    var incomingTripCount by mutableStateOf(0); private set
    private var tripCountPollJob: Job? = null

    val driverName: String get() = SessionManager.fullName

    init {
        viewModelScope.launch {
            LocationService.locationFlow.collect { location ->
                currentGeoPoint = GeoPoint(location.latitude, location.longitude)
            }
        }
        refreshProfile()
    }

    // ── Active trip recovery ──────────────────────────────────────────────────

    fun recoverActiveTrip() {
        viewModelScope.launch {
            repository.getActiveTrip(SessionManager.token)
                .onSuccess { trip ->
                    if (trip == null) { recoveredTripRoute = null; return@onSuccess }
                    val route = when {
                        trip.status == "offered" && trip.last_offer_by == "driver" ->
                            Screen.WaitingForRider.route(
                                tripId      = trip.id,
                                offeredFare = trip.offered_fare ?: 0.0,
                                defaultFare = 0.0
                            )
                        // NEGOT-RECOVER fix: rider has countered and driver killed the app.
                        // Previously incorrectly sent to IncomingTrips; now restores
                        // CounterDecisionScreen so the driver can respond.
                        trip.status == "offered" && trip.last_offer_by == "rider" ->
                            Screen.CounterDecision.route(
                                tripId             = trip.id,
                                riderFare          = trip.offered_fare ?: 0.0,
                                defaultFare        = 0.0,  // server enforces floor on submit
                                driverCounterCount = trip.driver_counter_count,
                                maxDriverCounters  = 3,    // matches hardcoded value in AppNavGraph
                            )
                        trip.status == "agreed" || trip.status == "arrived"
                                || trip.status == "in_progress" ->
                            Screen.ActiveTrip.route(trip.id)
                        else -> null
                    }
                    if (route != null) {
                        Log.d("MapViewModel", "Active trip recovery → $route")
                        recoveredTripRoute = route
                    }
                }
                .onFailure { Log.w("MapViewModel", "Active trip recovery failed", it) }
        }
    }

    fun clearRecoveredRoute() { recoveredTripRoute = null }

    // ── Permission & GPS ──────────────────────────────────────────────────────

    fun onPermissionResult(granted: Boolean) {
        permissionGranted = granted
        if (!granted && isOnline) isOnline = false
    }

    fun checkGps(context: Context) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!gpsEnabled && isOnline) toggleOnline(false, context)
    }

    // ── Profile ───────────────────────────────────────────────────────────────

    fun refreshProfile(context: Context? = null) {
        viewModelScope.launch {
            repository.getProfile(SessionManager.token)
                .onSuccess { profile ->
                    activeVehicleId = profile.active_vehicle_id
                    avatarUrl       = profile.avatar_url
                    avgRating       = profile.avg_rating
                    ratingCount     = profile.rating_count

                    val serviceRunning = LocationService.isRunning
                    when {
                        profile.is_online && !serviceRunning -> {
                            repository.goOffline(SessionManager.token)
                            isOnline = false
                            stopTripCountPolling()
                            Log.d("MapViewModel", "Cleaned up stale online state")
                        }
                        profile.is_online && serviceRunning -> {
                            isOnline = true
                            if (tripCountPollJob?.isActive != true) startTripCountPolling()
                        }
                        else -> {
                            isOnline = false
                            stopTripCountPolling()
                        }
                    }

                    if (profile.active_vehicle_id == null
                        && !isOnline
                        && !showNoVehicleDialog
                    ) {
                        showNoVehicleDialog = true
                    }
                }
        }
    }

    fun dismissNoVehicleDialog() { showNoVehicleDialog = false }

    // ── Online / Offline toggle ───────────────────────────────────────────────

    fun toggleOnline(online: Boolean, context: Context) {
        if (online) {
            if (!permissionGranted)      return
            if (!gpsEnabled)             return
            if (activeVehicleId == null) { showNoVehicleDialog = true; return }
        }
        isOnline = online
        val intent = Intent(context, LocationService::class.java)
        if (online) {
            context.startForegroundService(intent)
            startTripCountPolling()
        } else {
            context.stopService(intent)
            stopTripCountPolling()
        }
    }

    // ── Incoming trip count polling ───────────────────────────────────────────

    /**
     * Polls GET /driver/trips/incoming every 15 s while online and updates
     * [incomingTripCount] to drive the badge on the FAB. Lighter than
     * IncomingTripsScreen polling — only the count matters here.
     */
    private fun startTripCountPolling() {
        tripCountPollJob?.cancel()
        tripCountPollJob = viewModelScope.launch {
            while (true) {
                runCatching {
                    repository.getIncomingTrips(SessionManager.token)
                        .onSuccess { trips -> incomingTripCount = trips.size }
                        .onFailure { /* silent — badge disappears, no blocking action */ }
                }
                delay(15_000L)
            }
        }
    }

    private fun stopTripCountPolling() {
        tripCountPollJob?.cancel()
        tripCountPollJob = null
        incomingTripCount = 0
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    fun logout(context: Context) {
        context.stopService(Intent(context, LocationService::class.java))
        isOnline = false
        stopTripCountPolling()
        SessionManager.clear()
    }

    fun logout() {
        isOnline = false
        stopTripCountPolling()
        SessionManager.clear()
    }
}