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
    var routePoints        by mutableStateOf<List<GeoPoint>>(emptyList()); private set
    private var lastRouteFetchLat = 0.0
    private var lastRouteFetchLng = 0.0

    // ── Current driver GeoPoint for map marker ────────────────────────────────
    var currentGeoPoint by mutableStateOf(GeoPoint(0.0, 0.0)); private set

    private var realtimeChannel: RealtimeChannel? = null

    init {
        loadTrip()
        subscribeToUpdates()
        observeLocationForRouting()
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadTrip() {
        viewModelScope.launch {
            repository.getActiveTrip(SessionManager.token)
                .onSuccess { t ->
                    trip    = t
                    uiState = ActiveTripUiState.Idle
                }
                .onFailure { e ->
                    uiState = ActiveTripUiState.Error(e.message ?: "Failed to load trip")
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

        val (destLat, destLng) = when (t.status) {
            "agreed"      -> Pair(t.pickup_lat, t.pickup_lng)
            "in_progress" -> if (t.dropoff_lat != 0.0)
                Pair(t.dropoff_lat, t.dropoff_lng)
            else
                Pair(t.pickup_lat, t.pickup_lng)
            else          -> return
        }
        if (destLat == 0.0 && destLng == 0.0) return

        lastRouteFetchLat = driverLat
        lastRouteFetchLng = driverLng

        viewModelScope.launch {
            val pts = RouteHelper.fetchRoute(driverLat, driverLng, destLat?:0.0, destLng?:0.0)
            if (pts != null) routePoints = pts
        }
    }

    // ── Realtime subscription ─────────────────────────────────────────────────

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
                    Log.d(TAG, "Active trip status update → $status")
                    loadTrip()
                }.launchIn(viewModelScope)

                channel.subscribe()
            } catch (e: Exception) {
                Log.w(TAG, "Realtime subscription failed for active trip", e)
            }
        }
    }

    // ── Trip actions ──────────────────────────────────────────────────────────

    fun startTrip() {
        viewModelScope.launch {
            uiState = ActiveTripUiState.ActionLoading
            repository.startTrip(SessionManager.token, tripId)
                .onSuccess {
                    loadTrip()
                    // Reset route so it refetches toward dropoff after start
                    routePoints        = emptyList()
                    lastRouteFetchLat  = 0.0
                    lastRouteFetchLng  = 0.0
                }
                .onFailure { e ->
                    uiState = ActiveTripUiState.Error(
                        e.message ?: "Failed to start trip — please try again"
                    )
                }
        }
    }

    fun completeTrip() {
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
    }
}