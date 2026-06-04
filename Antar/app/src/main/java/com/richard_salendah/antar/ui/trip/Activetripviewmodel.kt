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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    // ── Location tracker — created lazily after trip loads (needs driverId) ──
    private var locationTracker: LocationTracker? = null
    private val _driverLocation = MutableStateFlow<DriverLocation?>(null)
    val driverLocation: StateFlow<DriverLocation?> = _driverLocation.asStateFlow()

    // ── Route state ───────────────────────────────────────────────────────────
    var routePoints by mutableStateOf<List<GeoPoint>>(emptyList())
        private set

    private var lastRouteFetchLat = 0.0
    private var lastRouteFetchLng = 0.0

    // ── Status tracking ───────────────────────────────────────────────────────
    private var statusChannel: RealtimeChannel? = null
    private var statusPoll:    Job?             = null
    private var started                         = false

    fun start(tripId: String, onCompleted: () -> Unit) {
        if (started) return
        started = true
        loadTrip(tripId)                         // creates tracker after trip loads
        subscribeRealtime(tripId, onCompleted)
        startStatusPolling(tripId, onCompleted)
        observeLocationForRouting()              // collect from _driverLocation whenever it emits
    }

    // ── Trip loading ──────────────────────────────────────────────────────────

    private fun loadTrip(tripId: String) {
        viewModelScope.launch {
            loading = true
            runCatching {
                val resp = api.getTrip(tripId)
                if (resp.isSuccessful) {
                    val t = resp.body()?.data ?: run {
                        error = "Gagal memuat detail perjalanan"
                        return@runCatching
                    }
                    trip = t

                    // Create and start location tracker now that we know the driver ID.
                    // RealtimeLocationTracker uses Supabase Realtime as primary and
                    // PollingLocationTracker (15s) as fallback.
                    if (locationTracker == null) {
                        val driverProfileId = t.driverId.orEmpty()
                        val tracker = RealtimeLocationTracker(
                            api             = api,
                            supabase        = supabase,
                            driverProfileId = driverProfileId,
                        )
                        locationTracker = tracker
                        tracker.start(tripId, viewModelScope)

                        // Bridge tracker emissions into our shared StateFlow
                        viewModelScope.launch {
                            tracker.location.collect { loc ->
                                _driverLocation.value = loc
                            }
                        }
                    }

                    // Trigger route fetch immediately if location already arrived
                    val loc = _driverLocation.value
                    if (loc != null) fetchRouteIfNeeded(loc.lat, loc.lng, t)

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
     */
    private fun observeLocationForRouting() {
        viewModelScope.launch {
            _driverLocation.collect { loc ->
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

    // ── Trip status Realtime ──────────────────────────────────────────────────

    private fun subscribeRealtime(tripId: String, onCompleted: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                val ch = supabase.channel("active_trip_$tripId-${System.currentTimeMillis()}")
                statusChannel = ch
                ch.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "trips"
                    filter("id", FilterOperator.EQ, tripId)
                }.onEach { action ->
                    val resp = api.getTrip(tripId)
                    if (resp.isSuccessful) {
                        val t = resp.body()?.data ?: return@onEach
                        trip = t
                        // Reset route destination when status transitions to in_progress
                        if (t.status == "in_progress") {
                            lastRouteFetchLat = 0.0
                            lastRouteFetchLng = 0.0
                        }
                        _driverLocation.value?.let { loc ->
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

    // ── Teardown ──────────────────────────────────────────────────────────────

    private fun teardown() {
        locationTracker?.stop()
        locationTracker = null
        statusPoll?.cancel()
        viewModelScope.launch {
            runCatching {
                statusChannel?.let { supabase.realtime.removeChannel(it) }
            }
        }
    }

    override fun onCleared() { super.onCleared(); teardown() }
}