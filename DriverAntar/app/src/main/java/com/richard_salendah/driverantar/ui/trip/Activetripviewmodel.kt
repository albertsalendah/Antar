package com.richard_salendah.driverantar.ui.trip

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.driverantar.data.model.DriverTripResponse
import com.richard_salendah.driverantar.data.remote.DriverRepository
import com.richard_salendah.driverantar.ui.service.LocationService
import com.richard_salendah.driverantar.ui.supabase.SupabaseClientHolder
import com.richard_salendah.driverantar.utils.SessionManager
import io.github.jan.supabase.postgrest.query.filter.FilterOperation
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import org.osmdroid.util.GeoPoint

sealed class ActiveTripUiState {
    object Loading       : ActiveTripUiState()
    object Idle          : ActiveTripUiState()
    object Confirming    : ActiveTripUiState()
    object ActionLoading : ActiveTripUiState()
    object Completed     : ActiveTripUiState()
    object Cancelled     : ActiveTripUiState()
    data class Error(val message: String) : ActiveTripUiState()
}

class ActiveTripViewModel(
    private val repository: DriverRepository,
    val tripId: String
) : ViewModel() {

    var trip    by mutableStateOf<DriverTripResponse?>(null); private set
    var uiState by mutableStateOf<ActiveTripUiState>(ActiveTripUiState.Loading); private set

    // ── Route state ───────────────────────────────────────────────────────────
    var routePoints           by mutableStateOf<List<GeoPoint>>(emptyList()); private set
    var routeDistanceMeters  by mutableStateOf<Double?>(null); private set
    var routeDurationSeconds by mutableStateOf<Double?>(null); private set
    private var lastRouteFetchLat = 0.0
    private var lastRouteFetchLng = 0.0

    // ── Current driver GeoPoint for map marker ────────────────────────────────
    var currentGeoPoint by mutableStateOf(GeoPoint(0.0, 0.0)); private set

    // Destination the current routePoints correspond to — invalidates the
    // cached route when the leg changes (pickup → dropoff) so a stale route
    // is never shown anchored to the wrong destination.
    private var cachedDestLat = 0.0
    private var cachedDestLng = 0.0

    // Timestamp of the last OSRM failure — avoids retrying OSRM every 50m
    // while it's down. Reset on success or on leg transition.
    private var lastOsrmFailureMs = 0L

    /**
     * Whether the complete button should be enabled.
     * For transport trips: driver must be within 150 m of dropoff.
     * For errand trips or when dropoff is unset: always enabled.
     */
    val canComplete: Boolean
        get() {
            val t = trip ?: return false
            if (t.trip_type == "errand" || t.dropoff_lat == 0.0) return true
            if (currentGeoPoint.latitude == 0.0) return false
            return RouteHelper.distanceMeters(
                currentGeoPoint.latitude, currentGeoPoint.longitude,
                t.dropoff_lat ?: 0.0, t.dropoff_lng ?: 0.0
            ) < 150.0
        }

    private var realtimeChannel: RealtimeChannel? = null

    // CONN-8: polling fallback for when WebSocket drops mid-trip
    private var statusPollJob: Job? = null

    init {
        loadTrip()
        subscribeToUpdates()
        startStatusPolling()
        observeLocationForRouting()
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadTrip() {
        viewModelScope.launch {
            repository.getActiveTrip(SessionManager.token)
                .onSuccess { t ->
                    trip    = t
                    uiState = ActiveTripUiState.Idle
                    val loc = currentGeoPoint
                    if (loc.latitude != 0.0 || loc.longitude != 0.0) {
                        fetchRouteIfNeeded(loc.latitude, loc.longitude)
                    }
                }
                .onFailure { e ->
                    uiState = ActiveTripUiState.Error(e.message ?: "Failed to load trip")
                }
        }
    }

    // ── Realtime subscription (primary) ───────────────────────────────────────

    private fun subscribeToUpdates() {
        viewModelScope.launch {
            try {
                val client  = SupabaseClientHolder.client
                val channel = client.channel("active-trip-$tripId-${System.currentTimeMillis()}")
                realtimeChannel = channel

                channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table  = "trips"
                    filter(FilterOperation("id", FilterOperator.EQ, tripId))
                }.onEach { action ->
                    val status = action.record["status"]?.jsonPrimitive?.content ?: return@onEach
                    Log.d(TAG, "Realtime → status=$status")
                    loadTrip()
                    applyTerminalStatus(status)
                }.launchIn(viewModelScope)

                channel.subscribe()
            } catch (e: Exception) {
                Log.w(TAG, "Realtime subscription failed — polling fallback active", e)
            }
        }
    }

    // ── Polling fallback (6 s) — CONN-8 ──────────────────────────────────────
    // Covers WebSocket drops on poor Talaud connectivity.
    // Skips during ActionLoading to avoid overwriting action results.

    private fun startStatusPolling() {
        statusPollJob = viewModelScope.launch {
            while (true) {
                delay(6_000L)

                // Don't interfere while a driver action is in flight
                if (uiState is ActiveTripUiState.ActionLoading) continue

                runCatching {
                    repository.getActiveTrip(SessionManager.token)
                        .onSuccess { t ->
                            if (t == null || t.id != tripId) return@onSuccess
                            trip = t
                            applyTerminalStatus(t.status)
                        }
                }.onFailure { e ->
                    Log.w(TAG, "Status poll error (non-fatal)", e)
                }
            }
        }
    }

    private fun applyTerminalStatus(status: String) {
        when (status) {
            "completed" -> uiState = ActiveTripUiState.Completed
            "cancelled" -> uiState = ActiveTripUiState.Cancelled
            else        -> if (uiState !is ActiveTripUiState.ActionLoading &&
                uiState !is ActiveTripUiState.Confirming) {
                uiState = ActiveTripUiState.Idle
            }
        }
    }

    // ── Location observation → route refresh ──────────────────────────────────

    private fun observeLocationForRouting() {
        viewModelScope.launch {
            LocationService.locationFlow.collect { location ->
                currentGeoPoint = GeoPoint(location.latitude, location.longitude)
                fetchRouteIfNeeded(location.latitude, location.longitude)
            }
        }
    }

    private fun fetchRouteIfNeeded(driverLat: Double, driverLng: Double) {
        val t = trip ?: return
        val moved = RouteHelper.distanceMeters(
            driverLat, driverLng, lastRouteFetchLat, lastRouteFetchLng
        )
        if (moved < 50.0 && routePoints.isNotEmpty()) return

        val (rawDestLat, rawDestLng) = when (t.status) {
            "agreed"      -> Pair(t.pickup_lat, t.pickup_lng)
            "in_progress" -> if (t.dropoff_lat != 0.0)
                Pair(t.dropoff_lat, t.dropoff_lng)
            else
                Pair(t.pickup_lat, t.pickup_lng)
            else          -> return
        }
        val destLat = rawDestLat ?: 0.0
        val destLng = rawDestLng ?: 0.0
        if (destLat == 0.0 && destLng == 0.0) return

        lastRouteFetchLat = driverLat
        lastRouteFetchLng = driverLng

        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val cooldownActive = now - lastOsrmFailureMs < OSRM_COOLDOWN_MS

            val fresh = if (cooldownActive) null
            else RouteHelper.fetchRoute(driverLat, driverLng, destLat, destLng)

            when {
                fresh != null -> {
                    routePoints           = fresh.points
                    routeDistanceMeters   = fresh.distanceMeters
                    routeDurationSeconds  = fresh.durationSeconds
                    cachedDestLat         = destLat
                    cachedDestLng         = destLng
                    lastOsrmFailureMs     = 0L
                }
                routePoints.isNotEmpty() &&
                        cachedDestLat == destLat && cachedDestLng == destLng -> {
                    if (!cooldownActive) lastOsrmFailureMs = now
                }
                else -> {
                    routePoints = listOf(
                        GeoPoint(driverLat, driverLng), GeoPoint(destLat, destLng)
                    )
                    routeDistanceMeters  = RouteHelper.distanceMeters(driverLat, driverLng, destLat, destLng)
                    routeDurationSeconds = null
                    cachedDestLat = destLat
                    cachedDestLng = destLng
                    if (!cooldownActive) lastOsrmFailureMs = now
                }
            }
        }
    }

    // ── Trip actions — CONN-7: guard against double-tap ───────────────────────

    fun arriveAtPickup() {
        if (uiState is ActiveTripUiState.ActionLoading) return
        viewModelScope.launch {
            uiState = ActiveTripUiState.ActionLoading
            repository.arriveAtPickup(SessionManager.token, tripId)
                .onSuccess { loadTrip() }
                .onFailure { e ->
                    uiState = ActiveTripUiState.Error(e.message ?: "Failed to mark arrived")
                }
        }
    }

    fun startTrip() {
        if (uiState is ActiveTripUiState.ActionLoading) return
        viewModelScope.launch {
            uiState = ActiveTripUiState.ActionLoading
            repository.startTrip(SessionManager.token, tripId)
                .onSuccess {
                    loadTrip()
                    // Reset route so it refetches toward dropoff after start
                    routePoints       = emptyList()
                    lastRouteFetchLat = 0.0
                    lastRouteFetchLng = 0.0
                    cachedDestLat     = 0.0
                    cachedDestLng     = 0.0
                    lastOsrmFailureMs = 0L
                    routeDistanceMeters  = null
                    routeDurationSeconds = null
                }
                .onFailure { e ->
                    uiState = ActiveTripUiState.Error(
                        e.message ?: "Failed to start trip — please try again"
                    )
                }
        }
    }

    fun completeTrip() {
        if (uiState is ActiveTripUiState.ActionLoading) return
        viewModelScope.launch {
            uiState = ActiveTripUiState.ActionLoading
            repository.completeTrip(SessionManager.token, tripId)
                .onSuccess { uiState = ActiveTripUiState.Completed }
                .onFailure { e ->
                    uiState = ActiveTripUiState.Error(
                        e.message ?: "Failed to complete trip — please try again"
                    )
                }
        }
    }

    fun requestCancel() { uiState = ActiveTripUiState.Confirming }
    fun dismissCancel() { uiState = ActiveTripUiState.Idle }

    fun confirmCancel() {
        if (uiState is ActiveTripUiState.ActionLoading) return
        viewModelScope.launch {
            uiState = ActiveTripUiState.ActionLoading
            repository.cancelTrip(SessionManager.token, tripId)
                .onSuccess { uiState = ActiveTripUiState.Cancelled }
                .onFailure { e ->
                    uiState = ActiveTripUiState.Error(e.message ?: "Failed to cancel trip")
                }
        }
    }

    fun clearError() { uiState = ActiveTripUiState.Idle }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        statusPollJob?.cancel()
        viewModelScope.launch {
            try {
                realtimeChannel?.let { ch ->
                    ch.unsubscribe()
                    SupabaseClientHolder.client.realtime.removeChannel(ch)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up Realtime channel", e)
            }
        }
    }

    companion object {
        private const val TAG = "ActiveTripVM"
        private const val OSRM_COOLDOWN_MS = 5 * 60_000L
    }
}