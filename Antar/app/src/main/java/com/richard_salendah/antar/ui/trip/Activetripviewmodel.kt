package com.richard_salendah.antar.ui.trip

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.antar.Antar
import com.richard_salendah.antar.data.model.TripResponse
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import org.osmdroid.util.GeoPoint

class ActiveTripViewModel(app: Application) : AndroidViewModel(app) {

    private val api      = (app as Antar).apiService
    private val supabase = (app as Antar).supabase

    var trip    by mutableStateOf<TripResponse?>(null)
    var loading by mutableStateOf(false)
    var error   by mutableStateOf<String?>(null)

    // ── Location tracker ──────────────────────────────────────────────────────
    private val locationTracker: LocationTracker = PollingLocationTracker(
        api        = api,
        intervalMs = 3_000L,
    )
    val driverLocation: StateFlow<DriverLocation?> = locationTracker.location

    // ── Route state ───────────────────────────────────────────────────────────
    var routePoints by mutableStateOf<List<GeoPoint>>(emptyList())
        private set

    private var lastRouteFetchLat = 0.0
    private var lastRouteFetchLng = 0.0

    // ── Status tracking ───────────────────────────────────────────────────────
    private var channel:    RealtimeChannel? = null
    private var statusPoll: Job?             = null
    private var started                      = false

    fun start(tripId: String, onCompleted: () -> Unit) {
        if (started) return
        started = true
        loadTrip(tripId)
        locationTracker.start(tripId, viewModelScope)
        subscribeRealtime(tripId, onCompleted)
        startStatusPolling(tripId, onCompleted)
        observeLocationForRouting()
    }

    // ── Trip loading ──────────────────────────────────────────────────────────

    private fun loadTrip(tripId: String) {
        viewModelScope.launch {
            loading = true
            runCatching {
                val resp = api.getTrip(tripId)
                if (resp.isSuccessful) {
                    trip = resp.body()?.data
                    // Trigger route fetch immediately once trip is loaded.
                    // driverLocation may already have a value from the first poll
                    // that completed before this coroutine finished.
                    val t   = trip ?: return@runCatching
                    val loc = driverLocation.value
                    if (loc != null) {
                        fetchRouteIfNeeded(loc.lat, loc.lng, t)
                    }
                } else {
                    error = "Gagal memuat detail perjalanan"
                }
            }.onFailure { error = "Tidak dapat terhubung ke server" }
            loading = false
        }
    }

    // ── Route fetching ────────────────────────────────────────────────────────

    /**
     * Observes driver location changes and re-fetches the OSRM route when the
     * driver has moved more than 50 metres from the last fetch point.
     * Also fires on the first non-null location regardless of distance.
     */
    private fun observeLocationForRouting() {
        viewModelScope.launch {
            driverLocation.collect { loc ->
                loc ?: return@collect
                val t = trip ?: return@collect
                fetchRouteIfNeeded(loc.lat, loc.lng, t)
            }
        }
    }

    private fun fetchRouteIfNeeded(
        driverLat: Double,
        driverLng: Double,
        t: TripResponse,
    ) {
        val moved = OsrmRouteHelper.distanceMeters(
            driverLat, driverLng,
            lastRouteFetchLat, lastRouteFetchLng,
        )
        if (moved < 50.0 && routePoints.isNotEmpty()) return

        val (destLat, destLng) = when (t.status) {
            "agreed", "arrived" -> Pair(t.pickupLat, t.pickupLng)
            "in_progress"       -> if (t.dropoffLat != 0.0)
                Pair(t.dropoffLat, t.dropoffLng)
            else
                Pair(t.pickupLat, t.pickupLng)
            else -> return
        }

        if (destLat == 0.0 && destLng == 0.0) return
        if (driverLat == 0.0 && driverLng == 0.0) return

        lastRouteFetchLat = driverLat
        lastRouteFetchLng = driverLng

        viewModelScope.launch {
            val pts = OsrmRouteHelper.fetchRoute(driverLat, driverLng, destLat, destLng)
            if (pts != null) routePoints = pts
        }
    }

    // ── Status Realtime ───────────────────────────────────────────────────────

    private fun subscribeRealtime(tripId: String, onCompleted: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                val ch = supabase.channel("active_trip_$tripId-${System.currentTimeMillis()}")
                channel = ch
                ch.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "trips"
                    filter("id", FilterOperator.EQ, tripId)
                }.onEach { action ->
                    val resp = api.getTrip(tripId)
                    if (resp.isSuccessful) {
                        val t = resp.body()?.data ?: return@onEach
                        trip = t
                        // Re-evaluate route destination when status changes
                        driverLocation.value?.let { loc ->
                            // Reset route so it refetches toward new destination
                            if (t.status == "in_progress") {
                                lastRouteFetchLat = 0.0
                                lastRouteFetchLng = 0.0
                            }
                            fetchRouteIfNeeded(loc.lat, loc.lng, t)
                        }
                        if (t.status == "completed") { teardown(); onCompleted() }
                    } else {
                        val status = action.record["status"]?.jsonPrimitive?.content
                        if (status == "completed") { teardown(); onCompleted() }
                    }
                }.launchIn(viewModelScope)
                ch.subscribe()
            }
        }
    }

    private fun startStatusPolling(tripId: String, onCompleted: () -> Unit) {
        statusPoll = viewModelScope.launch {
            while (true) {
                delay(6_000L)
                runCatching {
                    val resp = api.getTrip(tripId)
                    if (resp.isSuccessful) {
                        val t = resp.body()?.data ?: return@runCatching
                        trip = t
                        if (t.status == "completed") { teardown(); onCompleted() }
                    }
                }
            }
        }
    }

    private fun teardown() {
        locationTracker.stop()
        statusPoll?.cancel()
        viewModelScope.launch {
            runCatching { channel?.let { supabase.realtime.removeChannel(it) } }
        }
    }

    override fun onCleared() { super.onCleared(); teardown() }
}