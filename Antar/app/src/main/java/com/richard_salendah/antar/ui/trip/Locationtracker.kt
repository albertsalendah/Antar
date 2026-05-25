package com.richard_salendah.antar.ui.trip

import com.richard_salendah.antar.data.remote.ApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ── Location update model ─────────────────────────────────────────────────────

data class DriverLocation(
    val lat: Double,
    val lng: Double,
)

// ── Interface ─────────────────────────────────────────────────────────────────

/**
 * Abstraction over the driver location update mechanism.
 *
 * Current implementation: [PollingLocationTracker] — Option A.
 *   Every [intervalMs] milliseconds it calls GET /rider/trips/:id, reads
 *   driver_lat/driver_lng from the response, and emits to [location].
 *
 * Future implementation: [RealtimeLocationTracker] — Option B.
 *   Subscribes to driver_profiles row changes via Supabase Realtime WebSocket.
 *   Requires:
 *     1. driver_profiles added to supabase_realtime publication
 *     2. RLS policy allowing riders to read driver location rows
 *     3. driver_lat / driver_lng plain float columns on driver_profiles
 *        (PostGIS geography columns don't serialize cleanly over Realtime)
 *   See TODO_OPTION_B.md (create when starting migration).
 *
 * [ActiveTripViewModel] depends only on this interface. Swapping Option B in
 * requires implementing [RealtimeLocationTracker] and changing one line in
 * the ViewModel factory.
 */
interface LocationTracker {
    /** Emits the latest known driver coordinates. Null until first update. */
    val location: StateFlow<DriverLocation?>

    /** Start tracking. Safe to call multiple times — implementations must guard. */
    fun start(tripId: String, scope: CoroutineScope)

    /** Release resources (cancel coroutines, close channels). */
    fun stop()
}

// ── Option A — polling ────────────────────────────────────────────────────────

/**
 * Polls GET /rider/trips/:id every [intervalMs] milliseconds and extracts
 * driver_lat / driver_lng from the response.
 *
 * Pro:  zero infrastructure, works on any connectivity, reliable fallback.
 * Con:  location is up to [intervalMs] stale; generates continuous HTTP traffic.
 *
 * Default interval: 3 000 ms — fast enough to look smooth on small islands
 * where drivers travel slowly, while not hammering the server.
 */
class PollingLocationTracker(
    private val api: ApiService,
    private val intervalMs: Long = 3_000L,
) : LocationTracker {

    private val _location = MutableStateFlow<DriverLocation?>(null)
    override val location: StateFlow<DriverLocation?> = _location.asStateFlow()

    private var job: Job?    = null
    private var started      = false

    override fun start(tripId: String, scope: CoroutineScope) {
        if (started) return
        started = true
        job = scope.launch {
            while (true) {
                runCatching {
                    val resp = api.getTrip(tripId)
                    if (resp.isSuccessful) {
                        val t = resp.body()?.data ?: return@runCatching
                        // Only emit when the driver has a real GPS fix
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

// ── Option B — Realtime (stub) ────────────────────────────────────────────────

/**
 * TODO — implement when the app is stable and ready for Option B migration.
 *
 * Steps to implement:
 *  1. Run in Supabase SQL editor:
 *       ALTER PUBLICATION supabase_realtime ADD TABLE driver_profiles;
 *       ALTER TABLE driver_profiles
 *           ADD COLUMN IF NOT EXISTS last_lat DOUBLE PRECISION,
 *           ADD COLUMN IF NOT EXISTS last_lng DOUBLE PRECISION;
 *       -- Update driver location handler to write last_lat/last_lng alongside last_location
 *       CREATE POLICY "riders can read driver location" ON driver_profiles
 *           FOR SELECT TO authenticated USING (true);
 *
 *  2. Implement this class:
 *       - Subscribe to driver_profiles UPDATE where id = driverProfileId
 *       - Read last_lat / last_lng from action.record
 *       - Emit DriverLocation
 *       - Keep PollingLocationTracker running as fallback with a longer interval (e.g. 15s)
 *         in case the WebSocket drops on poor Talaud connectivity
 *
 *  3. In ActiveTripViewModel, replace:
 *       PollingLocationTracker(api)
 *     with:
 *       RealtimeLocationTracker(supabase, driverProfileId, fallback = PollingLocationTracker(api, 15_000))
 *
 * Current behaviour: immediately delegates to PollingLocationTracker so the
 * stub can be wired in without breaking anything.
 */
class RealtimeLocationTracker(
    private val api: ApiService,
    // supabase: SupabaseClient   ← add when implementing
    // driverProfileId: String    ← add when implementing
) : LocationTracker {

    // Delegate to polling until Option B is implemented
    private val delegate = PollingLocationTracker(api)

    override val location: StateFlow<DriverLocation?> = delegate.location

    override fun start(tripId: String, scope: CoroutineScope) {
        // TODO: open Supabase Realtime channel here, then start delegate as fallback
        delegate.start(tripId, scope)
    }

    override fun stop() {
        // TODO: close Realtime channel here
        delegate.stop()
    }
}