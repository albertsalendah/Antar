package com.richard_salendah.antar.ui.trip
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
// Used as the primary fallback inside RealtimeLocationTracker (15s interval).
// ─────────────────────────────────────────────────────────────────────────────

class PollingLocationTracker(
    private val api: ApiService,
    private val intervalMs: Long = 15_000L,
) : LocationTracker {

    private val _location = MutableStateFlow<DriverLocation?>(null)
    override val location: StateFlow<DriverLocation?> = _location.asStateFlow()

    private var job: Job? = null
    private var started = false

    override fun start(tripId: String, scope: CoroutineScope) {
        if (started) return
        started = true
        job = scope.launch {
            while (true) {
                runCatching {
                    val resp = api.getTrip(tripId)
                    if (resp.isSuccessful) {
                        val t = resp.body()?.data ?: return@runCatching
                        if (t.driverLat != 0.0 || t.driverLng != 0.0) {
                            _location.value = DriverLocation(t.driverLat, t.driverLng)
                        }
                    }
                }
                delay(intervalMs)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Realtime — subscribes to driver_profiles UPDATE on Supabase Realtime.
// Falls back to PollingLocationTracker (15s) when WebSocket is unavailable
// (poor connectivity expected on Talaud islands).
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

    // 15-second polling fallback — provides updates when WebSocket drops
    private val pollingFallback = PollingLocationTracker(api, intervalMs = 15_000L)

    private var realtimeChannel: RealtimeChannel? = null

    override fun start(tripId: String, scope: CoroutineScope) {
        // Always start polling fallback first — Realtime may take a moment to connect
        pollingFallback.start(tripId, scope)
        scope.launch {
            pollingFallback.location.collect { loc ->
                loc ?: return@collect
                _location.value = loc   // polling fills gaps when Realtime is down
            }
        }

        if (driverProfileId.isBlank()) return  // no driver assigned yet

        scope.launch {
            runCatching {
                val ch = supabase.channel(
                    "driver-loc-$driverProfileId-${System.currentTimeMillis()}"
                )
                realtimeChannel = ch

                ch.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "driver_profiles"
                    filter("id", FilterOperator.EQ, driverProfileId)
                }.onEach { action ->
                    val lat = action.record["last_lat"]
                        ?.jsonPrimitive?.doubleOrNull ?: return@onEach
                    val lng = action.record["last_lng"]
                        ?.jsonPrimitive?.doubleOrNull ?: return@onEach
                    if (lat != 0.0 || lng != 0.0) {
                        _location.value = DriverLocation(lat, lng)
                    }
                }.launchIn(scope)

                ch.subscribe()
            }.onFailure {
                // Realtime setup failed — polling fallback already running, no action needed
            }
        }
    }

    override fun stop() {
        pollingFallback.stop()
        realtimeChannel?.let { ch ->
            // Best-effort cleanup — may be called after viewModelScope cancels
            kotlinx.coroutines.GlobalScope.launch {
                runCatching {
                    ch.unsubscribe()
                    supabase.realtime.removeChannel(ch)
                }
            }
        }
        realtimeChannel = null
    }
}