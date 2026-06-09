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

    // CONN-3: exposed when N consecutive poll failures occur so the screen
    // can show a warning instead of silently showing stale data.
    var connectionLost by mutableStateOf(false)
        private set

    // CONN-5: tracks when the last status update was received (millis).
    // The screen observes this and shows a refresh hint after 30 s of silence.
    var lastStatusUpdateMs by mutableStateOf(System.currentTimeMillis())
        private set

    // ── Location tracker ──────────────────────────────────────────────────────
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

    // ── Polling failure tracking (CONN-3) ─────────────────────────────────────
    private var consecutivePollFailures = 0
    private val pollFailureThreshold    = 3

    fun start(tripId: String, onCompleted: () -> Unit) {
        if (started) return
        started = true
        loadTrip(tripId)
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
                    val t = resp.body()?.data ?: run {
                        error = "Gagal memuat detail perjalanan"
                        return@runCatching
                    }
                    trip = t
                    lastStatusUpdateMs = System.currentTimeMillis()

                    if (locationTracker == null) {
                        val driverProfileId = t.driverId.orEmpty()
                        val tracker = RealtimeLocationTracker(
                            api             = api,
                            supabase        = supabase,
                            driverProfileId = driverProfileId,
                        )
                        locationTracker = tracker
                        tracker.start(tripId, viewModelScope)

                        viewModelScope.launch {
                            tracker.location.collect { loc ->
                                _driverLocation.value = loc
                            }
                        }
                    }

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

    // CONN-4: called by the screen when connectivity is restored.
    // Resets the last fetch point so fetchRouteIfNeeded triggers a fresh fetch
    // on the next location emission.
    fun retryRoute() {
        lastRouteFetchLat = 0.0
        lastRouteFetchLng = 0.0
        val loc = _driverLocation.value ?: return
        val t   = trip ?: return
        fetchRouteIfNeeded(loc.lat, loc.lng, t)
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
                        lastStatusUpdateMs  = System.currentTimeMillis()
                        consecutivePollFailures = 0
                        connectionLost      = false

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

    // CONN-3 + CONN-5: polling tracks consecutive failures and records the
    // timestamp of the last successful status update.
    private fun startStatusPolling(tripId: String, onCompleted: () -> Unit) {
        statusPoll = viewModelScope.launch {
            while (true) {
                delay(6_000L)
                val success = runCatching {
                    val resp = api.getTrip(tripId)
                    if (resp.isSuccessful) {
                        val t = resp.body()?.data ?: return@runCatching false
                        trip = t
                        lastStatusUpdateMs      = System.currentTimeMillis()
                        consecutivePollFailures = 0
                        connectionLost          = false
                        if (t.status == "completed") { teardown(); onCompleted() }
                        true
                    } else {
                        false
                    }
                }.getOrElse { false }

                if (!success) {
                    consecutivePollFailures++
                    if (consecutivePollFailures >= pollFailureThreshold) {
                        // CONN-3: surface connection loss to the screen
                        connectionLost = true
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