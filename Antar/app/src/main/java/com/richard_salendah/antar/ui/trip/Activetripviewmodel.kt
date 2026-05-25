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

    // ── Location tracker (Option A active, Option B ready) ────────────────────
    // To migrate to Option B: replace PollingLocationTracker with
    // RealtimeLocationTracker once the stub in LocationTracker.kt is implemented.
    private val locationTracker: LocationTracker = PollingLocationTracker(
        api         = api,
        intervalMs  = 3_000L,
    )
    val driverLocation: StateFlow<DriverLocation?> = locationTracker.location

    // ── Route state ───────────────────────────────────────────────────────────
    var routePoints by mutableStateOf<List<GeoPoint>>(emptyList())
        private set

    // Track where we last fetched a route from so we can apply the 50m threshold
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

    private fun loadTrip(tripId: String) {
        viewModelScope.launch {
            loading = true
            runCatching {
                val resp = api.getTrip(tripId)
                if (resp.isSuccessful) {
                    trip = resp.body()?.data
                    // Trigger an initial route fetch once we know the trip coords
                    val t = trip ?: return@runCatching
                    val driverLoc = driverLocation.value
                    if (driverLoc != null) {
                        fetchRouteIfNeeded(driverLoc.lat, driverLoc.lng, t)
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
     * This keeps the route line accurate without hammering OSRM on every 3s poll.
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
        // Only re-fetch when driver has moved >50 m or we have no route yet
        if (moved < 50.0 && routePoints.isNotEmpty()) return

        val (destLat, destLng) = when (t.status) {
            "agreed"      -> Pair(t.pickupLat, t.pickupLng)
            "in_progress" -> if (t.dropoffLat != 0.0)
                Pair(t.dropoffLat, t.dropoffLng)
            else
                Pair(t.pickupLat, t.pickupLng)
            else          -> return
        }

        if (destLat == 0.0 && destLng == 0.0) return

        lastRouteFetchLat = driverLat
        lastRouteFetchLng = driverLng

        viewModelScope.launch {
            val pts = OsrmRouteHelper.fetchRoute(driverLat, driverLng, destLat, destLng)
            if (pts != null) routePoints = pts
        }
    }

    // ── Status realtime ───────────────────────────────────────────────────────

    private fun subscribeRealtime(tripId: String, onCompleted: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                val ch = supabase.channel("active_trip_$tripId")
                channel = ch
                ch.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "trips"
                    filter("id", FilterOperator.EQ, tripId)
                }.onEach { action ->
                    val status = action.record["status"]?.jsonPrimitive?.content
                    val resp   = api.getTrip(tripId)
                    if (resp.isSuccessful) {
                        val t = resp.body()?.data ?: return@onEach
                        trip = t
                        // Re-evaluate route destination when status changes
                        driverLocation.value?.let { loc ->
                            fetchRouteIfNeeded(loc.lat, loc.lng, t)
                        }
                        if (t.status == "completed") { teardown(); onCompleted() }
                    } else if (status == "completed") {
                        teardown(); onCompleted()
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