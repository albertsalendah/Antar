package com.richard_salendah.antar.ui.trip

import android.app.Application
import android.util.Log
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive
import org.osmdroid.util.GeoPoint

private const val TAG = "ActiveTripVM_Rider"

class ActiveTripViewModel(app: Application) : AndroidViewModel(app) {

    private val api      = (app as Antar).apiService
    private val supabase = (app as Antar).supabase

    var trip    by mutableStateOf<TripResponse?>(null)
    var loading by mutableStateOf(false)
    var error   by mutableStateOf<String?>(null)

    // CONN-3: exposed when N consecutive poll failures occur so the screen
    // can surface a warning instead of silently showing stale data.
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

    // Internal StateFlow for trip so we can combine it with driverLocation.
    // The public `trip` compose state is kept in sync with this.
    private val _trip = MutableStateFlow<TripResponse?>(null)

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
            Log.d(TAG, "loadTrip: fetching tripId=$tripId")
            runCatching {
                val resp = api.getTrip(tripId)
                if (resp.isSuccessful) {
                    val t = resp.body()?.data ?: run {
                        error = "Gagal memuat detail perjalanan"
                        Log.e(TAG, "loadTrip: response OK but data is null")
                        return@runCatching
                    }
                    trip  = t
                    _trip.value = t
                    lastStatusUpdateMs = System.currentTimeMillis()
                    Log.d(TAG, "loadTrip: success status=${t.status} " +
                            "driverId=${t.driverId} " +
                            "driverLat=${t.driverLat} driverLng=${t.driverLng}")

                    if (locationTracker == null) {
                        val driverProfileId = t.driverId.orEmpty()
                        Log.d(TAG, "loadTrip: creating RealtimeLocationTracker " +
                                "driverProfileId=$driverProfileId")
                        val tracker = RealtimeLocationTracker(
                            api             = api,
                            supabase        = supabase,
                            driverProfileId = driverProfileId,
                        )
                        locationTracker = tracker
                        tracker.start(tripId, viewModelScope)

                        viewModelScope.launch {
                            tracker.location.collect { loc ->
                                if (loc != null) {
                                    Log.d(TAG, "locationTracker emit: lat=${loc.lat} lng=${loc.lng}")
                                }
                                _driverLocation.value = loc
                            }
                        }
                    }
                } else {
                    error = "Gagal memuat detail perjalanan"
                    Log.e(TAG, "loadTrip: HTTP ${resp.code()}")
                }
            }.onFailure { e ->
                error = "Tidak dapat terhubung ke server"
                Log.e(TAG, "loadTrip: exception — ${e.message}")
            }
            loading = false
        }
    }

    // ── Route fetching ────────────────────────────────────────────────────────

    /**
     * TODO-7 fix: observes BOTH _driverLocation and _trip via combine so a route
     * fetch is triggered whenever either changes.
     *
     * Previous bug: observeLocationForRouting() gated on `trip` (Compose state) being
     * non-null at collection time. Since trip loads asynchronously, the first location
     * emission often arrived before loadTrip() finished — trip was null, the call was
     * skipped, and no subsequent trigger re-ran fetchRouteIfNeeded for that location.
     *
     * Fix: combine(_driverLocation, _trip) so any change to either restarts the check.
     */
    private fun observeLocationForRouting() {
        combine(_driverLocation, _trip) { loc, t -> Pair(loc, t) }
            .onEach { (loc, t) ->
                if (loc == null) {
                    Log.v(TAG, "observeLocationForRouting: driverLocation is null — waiting")
                    return@onEach
                }
                if (t == null) {
                    Log.v(TAG, "observeLocationForRouting: trip not loaded yet — waiting")
                    return@onEach
                }
                Log.d(TAG, "observeLocationForRouting: loc=(${loc.lat},${loc.lng}) " +
                        "trip.status=${t.status}")
                fetchRouteIfNeeded(loc.lat, loc.lng, t)
            }
            .launchIn(viewModelScope)
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

        if (moved < 50.0 && routePoints.isNotEmpty()) {
            Log.v(TAG, "fetchRouteIfNeeded: skipped — driver moved only ${moved.toInt()}m " +
                    "and route already drawn (${routePoints.size} points)")
            return
        }

        val (destLat, destLng) = when (t.status) {
            "agreed", "arrived" -> {
                Log.d(TAG, "fetchRouteIfNeeded: routing to PICKUP (${t.pickupLat},${t.pickupLng})")
                Pair(t.pickupLat, t.pickupLng)
            }
            "in_progress" -> if (t.dropoffLat != 0.0) {
                Log.d(TAG, "fetchRouteIfNeeded: routing to DROPOFF (${t.dropoffLat},${t.dropoffLng})")
                Pair(t.dropoffLat, t.dropoffLng)
            } else {
                Log.d(TAG, "fetchRouteIfNeeded: in_progress but dropoff is 0,0 — routing to pickup")
                Pair(t.pickupLat, t.pickupLng)
            }
            else -> {
                Log.d(TAG, "fetchRouteIfNeeded: status=${t.status} — no route needed")
                return
            }
        }

        if (destLat == 0.0 && destLng == 0.0) {
            Log.w(TAG, "fetchRouteIfNeeded: destination is 0,0 — skipping")
            return
        }
        if (driverLat == 0.0 && driverLng == 0.0) {
            Log.w(TAG, "fetchRouteIfNeeded: driver origin is 0,0 — skipping")
            return
        }

        lastRouteFetchLat = driverLat
        lastRouteFetchLng = driverLng

        viewModelScope.launch {
            Log.d(TAG, "fetchRouteIfNeeded: requesting route " +
                    "origin=($driverLat,$driverLng) dest=($destLat,$destLng) " +
                    "movedSinceLast=${moved.toInt()}m")

            // TODO-7: use fetchRouteWithFallback so a straight line is drawn when
            // OSRM has no road data (common in Talaud). Returns empty list only if
            // either origin or destination is 0,0.
            val pts = OsrmRouteHelper.fetchRouteWithFallback(
                driverLat, driverLng, destLat, destLng
            )

            if (pts.isNotEmpty()) {
                routePoints = pts
                val type = if (pts.size == 2) "straight-line" else "road"
                Log.d(TAG, "fetchRouteIfNeeded: route drawn — $type, ${pts.size} points")
            } else {
                Log.w(TAG, "fetchRouteIfNeeded: fetchRouteWithFallback returned empty — not drawing")
            }
        }
    }

    // CONN-4: called by the screen when connectivity is restored.
    // Resets the last fetch point so fetchRouteIfNeeded triggers a fresh fetch
    // on the next location emission.
    fun retryRoute() {
        Log.d(TAG, "retryRoute: connectivity restored — resetting fetch state")
        lastRouteFetchLat = 0.0
        lastRouteFetchLng = 0.0
        val loc = _driverLocation.value ?: return
        val t   = _trip.value ?: return
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
                    Log.d(TAG, "Realtime: trips UPDATE received")
                    val resp = api.getTrip(tripId)
                    if (resp.isSuccessful) {
                        val t = resp.body()?.data ?: return@onEach
                        trip = t
                        _trip.value = t
                        lastStatusUpdateMs      = System.currentTimeMillis()
                        consecutivePollFailures = 0
                        connectionLost          = false
                        Log.d(TAG, "Realtime: trip refreshed status=${t.status}")

                        if (t.status == "in_progress") {
                            Log.d(TAG, "Realtime: in_progress — resetting route fetch state")
                            lastRouteFetchLat = 0.0
                            lastRouteFetchLng = 0.0
                        }
                        if (t.status == "completed") { teardown(); onCompleted() }
                    } else {
                        val status = action.record["status"]?.jsonPrimitive?.content
                        Log.w(TAG, "Realtime: getTrip failed HTTP ${resp.code()}, " +
                                "fallback status from payload=$status")
                        if (status == "completed") { teardown(); onCompleted() }
                    }
                }.launchIn(viewModelScope)
                ch.subscribe()
                Log.d(TAG, "Realtime: subscribed to trips id=$tripId")
            }.onFailure { e ->
                Log.e(TAG, "Realtime: subscription failed — ${e.message}")
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
                        _trip.value = t
                        lastStatusUpdateMs      = System.currentTimeMillis()
                        consecutivePollFailures = 0
                        connectionLost          = false
                        if (t.status == "completed") { teardown(); onCompleted() }
                        true
                    } else {
                        Log.w(TAG, "statusPoll: HTTP ${resp.code()}")
                        false
                    }
                }.getOrElse { e ->
                    Log.w(TAG, "statusPoll: exception — ${e.message}")
                    false
                }

                if (!success) {
                    consecutivePollFailures++
                    Log.w(TAG, "statusPoll: failure #$consecutivePollFailures")
                    if (consecutivePollFailures >= pollFailureThreshold) {
                        connectionLost = true
                        Log.e(TAG, "statusPoll: connectionLost=true after " +
                                "$consecutivePollFailures consecutive failures")
                    }
                }
            }
        }
    }

    // ── Teardown ──────────────────────────────────────────────────────────────

    private fun teardown() {
        Log.d(TAG, "teardown: stopping trackers and Realtime")
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