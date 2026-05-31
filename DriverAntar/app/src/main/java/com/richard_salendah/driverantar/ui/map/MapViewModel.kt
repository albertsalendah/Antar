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
                        trip.status == "offered" && trip.last_offer_by == "rider" ->
                            Screen.IncomingTrips.route
                        trip.status == "agreed" || trip.status == "arrived" || trip.status == "in_progress" ->
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

                    // ── Rating ────────────────────────────────────────────────
                    avgRating   = profile.avg_rating
                    ratingCount = profile.rating_count

                    val serviceRunning = LocationService.isRunning
                    when {
                        profile.is_online && !serviceRunning -> {
                            repository.goOffline(SessionManager.token)
                            isOnline = false
                            Log.d("MapViewModel", "Cleaned up stale online state")
                        }
                        profile.is_online && serviceRunning -> isOnline = true
                        else                                -> isOnline = false
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
        if (online) context.startForegroundService(intent)
        else        context.stopService(intent)
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    fun logout(context: Context) {
        context.stopService(Intent(context, LocationService::class.java))
        isOnline = false
        SessionManager.clear()
    }

    fun logout() {
        isOnline = false
        SessionManager.clear()
    }
}