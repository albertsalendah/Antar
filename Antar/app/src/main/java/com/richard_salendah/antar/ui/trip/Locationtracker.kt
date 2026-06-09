package com.richard_salendah.antar.ui.trip
import android.util.Log
import com.richard_salendah.antar.data.remote.ApiService
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "LocationTracker"

data class DriverLocation(val lat: Double, val lng: Double)

// ─────────────────────────────────────────────────────────────────────────────
// Interface
// ─────────────────────────────────────────────────────────────────────────────

interface LocationTracker {
    val location: StateFlow<DriverLocation?>
    fun start(tripId: String, scope: CoroutineScope)
    fun stop()
}

// ─────────────────────────────────────────────────────────────────────────────
// Polling — calls GET /rider/trips/:id every [intervalMs] ms
// Default interval reduced to 5s (was 15s) so worst-case lag when Realtime
// drops is ~8s instead of ~18s. Matches the driver's GPS update interval.
// ─────────────────────────────────────────────────────────────────────────────

class PollingLocationTracker(
    private val api: ApiService,
    private val intervalMs: Long = 5_000L,
) : LocationTracker {

    private val _location = MutableStateFlow<DriverLocation?>(null)
    override val location: StateFlow<DriverLocation?> = _location.asStateFlow()

    private var job: Job? = null
    private var started = false

    override fun start(tripId: String, scope: CoroutineScope) {
        if (started) return
        started = true
        Log.d(TAG, "PollingTracker started — interval=${intervalMs}ms tripId=$tripId")
        job = scope.launch {
            while (true) {
                runCatching {
                    val resp = api.getTrip(tripId)
                    if (resp.isSuccessful) {
                        val t = resp.body()?.data ?: return@runCatching
                        if (t.driverLat != 0.0 || t.driverLng != 0.0) {
                            val prev = _location.value
                            _location.value = DriverLocation(t.driverLat, t.driverLng)
                            if (prev?.lat != t.driverLat || prev?.lng != t.driverLng) {
                                Log.d(TAG, "Poll: driver moved → lat=${t.driverLat} lng=${t.driverLng}")
                            } else {
                                Log.v(TAG, "Poll: driver position unchanged (${t.driverLat}, ${t.driverLng})")
                            }
                        } else {
                            Log.w(TAG, "Poll: driver_lat/lng is 0,0 — server returned no location yet")
                        }
                    } else {
                        Log.w(TAG, "Poll: getTrip failed HTTP ${resp.code()}")
                    }
                }.onFailure { e ->
                    Log.w(TAG, "Poll: getTrip exception — ${e.message}")
                }
                delay(intervalMs)
            }
        }
    }

    override fun stop() {
        Log.d(TAG, "PollingTracker stopped")
        job?.cancel()
        job = null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Realtime — subscribes to driver_profiles UPDATE on Supabase Realtime.
// Falls back to PollingLocationTracker (5s) when WebSocket is unavailable.
//
// Requires:
//   • driver_profiles is in supabase_realtime publication  (✓ already done)
//   • RLS policy "riders can read driver location" exists  (✓ already done)
// ─────────────────────────────────────────────────────────────────────────────

class RealtimeLocationTracker(
    private val api: ApiService,
    private val supabase: SupabaseClient,
    private val driverProfileId: String,
) : LocationTracker {

    private val _location = MutableStateFlow<DriverLocation?>(null)
    override val location: StateFlow<DriverLocation?> = _location.asStateFlow()

    // 5s polling fallback — provides updates when WebSocket drops.
    // Reduced from 15s to match driver GPS interval and minimise visible lag.
    private val pollingFallback = PollingLocationTracker(api, intervalMs = 5_000L)

    private var realtimeChannel: RealtimeChannel? = null

    override fun start(tripId: String, scope: CoroutineScope) {
        // Always start polling fallback first — Realtime may take a moment to connect.
        // Polling also handles the initial location load before Realtime fires its
        // first UPDATE event.
        pollingFallback.start(tripId, scope)
        scope.launch {
            pollingFallback.location.collect { loc ->
                loc ?: return@collect
                _location.value = loc   // polling fills gaps when Realtime is down
            }
        }

        if (driverProfileId.isBlank()) {
            Log.w(TAG, "RealtimeTracker: driverProfileId is blank — Realtime skipped, polling only")
            return
        }

        scope.launch {
            runCatching {
                val channelName = "driver-loc-$driverProfileId-${System.currentTimeMillis()}"
                Log.d(TAG, "RealtimeTracker: subscribing channel=$channelName")
                val ch = supabase.channel(channelName)
                realtimeChannel = ch

                ch.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "driver_profiles"
                    filter("id", FilterOperator.EQ, driverProfileId)
                }.onEach { action ->
                    val lat = action.record["last_lat"]
                        ?.jsonPrimitive?.doubleOrNull
                    val lng = action.record["last_lng"]
                        ?.jsonPrimitive?.doubleOrNull

                    if (lat == null || lng == null) {
                        Log.w(TAG, "Realtime: UPDATE received but last_lat/last_lng missing in payload")
                        return@onEach
                    }
                    if (lat == 0.0 && lng == 0.0) {
                        Log.w(TAG, "Realtime: UPDATE received but lat/lng are 0,0 — ignoring")
                        return@onEach
                    }

                    val prev = _location.value
                    _location.value = DriverLocation(lat, lng)
                    Log.d(TAG, "Realtime: driver moved → lat=$lat lng=$lng " +
                            "(prev=${prev?.lat}, ${prev?.lng})")
                }.launchIn(scope)

                ch.subscribe()
                Log.d(TAG, "RealtimeTracker: subscribed to driver_profiles id=$driverProfileId")
            }.onFailure { e ->
                Log.e(TAG, "RealtimeTracker: subscription failed — polling fallback active. ${e.message}")
            }
        }
    }

    override fun stop() {
        Log.d(TAG, "RealtimeTracker: stopping")
        pollingFallback.stop()
        realtimeChannel?.let { ch ->
            // Best-effort cleanup — may be called after viewModelScope cancels
            kotlinx.coroutines.GlobalScope.launch {
                runCatching {
                    ch.unsubscribe()
                    supabase.realtime.removeChannel(ch)
                    Log.d(TAG, "RealtimeTracker: channel removed")
                }
            }
        }
        realtimeChannel = null
    }
}