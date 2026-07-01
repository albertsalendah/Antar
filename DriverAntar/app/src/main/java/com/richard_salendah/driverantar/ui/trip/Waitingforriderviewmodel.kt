package com.richard_salendah.driverantar.ui.trip

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.driverantar.data.remote.DriverRepository
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

sealed class WaitingUiState {
    object Waiting  : WaitingUiState()
    object Agreed   : WaitingUiState()
    object Rejected : WaitingUiState()
    /**
     * Rider sent a counter — carry both the fare and the current driver counter
     * count so AppNavGraph can pass the accurate value to CounterDecisionViewModel.
     */
    data class RiderCountered(
        val riderFare: Double,
        val driverCounterCount: Int = 0
    ) : WaitingUiState()
    object Cancelled : WaitingUiState()
    /** Driver pulled back their own offer before the rider responded. */
    object Withdrawn : WaitingUiState()
    data class Error(val message: String) : WaitingUiState()
}

class WaitingForRiderViewModel(
    private val repository: DriverRepository,
    val tripId: String,
    val offeredFare: Double,
    /** Admin floor price — threaded through so CounterDecision enforces the right minimum */
    val defaultFare: Double
) : ViewModel() {

    var uiState by mutableStateOf<WaitingUiState>(WaitingUiState.Waiting); private set
    /** Guards against double-tap on the Withdraw button while the request is in flight */
    var isWithdrawing by mutableStateOf(false); private set

    private var realtimeChannel: RealtimeChannel? = null
    private var pollJob: Job? = null

    init {
        subscribeToTripChanges()
        startPolling()
    }

    // ── Realtime (primary) ────────────────────────────────────────────────────

    private fun subscribeToTripChanges() {
        viewModelScope.launch {
            try {
                val client = SupabaseClientHolder.client

                // Timestamp suffix prevents channel-name collision when navigating
                // CounterDecision → WaitingForRider before the old VM's onCleared finishes.
                val channel = client.channel("trip-watch-$tripId-${System.currentTimeMillis()}")
                realtimeChannel = channel

                channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "trips"
                    filter(FilterOperation("id", FilterOperator.EQ, tripId))
                }.onEach { action ->
                    handleRealtimeUpdate(action.record)
                }.launchIn(viewModelScope)

                channel.subscribe()
                Log.d(TAG, "Subscribed to trip $tripId")

            } catch (e: Exception) {
                // Realtime unavailable — polling fallback already running, no action needed.
                // Do NOT set Error state; the waiting spinner stays visible and polling handles it.
                Log.e(TAG, "Realtime subscription failed — polling fallback active", e)
            }
        }
    }

    private fun handleRealtimeUpdate(
        record: Map<String, kotlinx.serialization.json.JsonElement>
    ) {
        val status             = record["status"]?.jsonPrimitive?.content              ?: return
        val lastOfferBy        = record["last_offer_by"]?.jsonPrimitive?.content
        val newFare            = record["offered_fare"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val driverCounterCount = record["driver_counter_count"]?.jsonPrimitive?.content
            ?.toIntOrNull() ?: 0

        Log.d(TAG, "Realtime → status=$status last_offer_by=$lastOfferBy")
        applyStatus(status, lastOfferBy, newFare, driverCounterCount)
    }

    // ── Polling fallback (5 s) ────────────────────────────────────────────────
    // Fires every 5 s covering WebSocket drops on poor Talaud connectivity.
    // Stops automatically once Realtime (or itself) resolves to a terminal state.

    private fun startPolling() {
        pollJob = viewModelScope.launch {
            while (true) {
                delay(5_000L)

                // Stop polling once state is resolved
                val current = uiState
                if (current !is WaitingUiState.Waiting) break

                runCatching {
                    repository.getActiveTrip(SessionManager.token)
                        .onSuccess { trip ->
                            when {
                                trip == null -> {
                                    // Trip is no longer in active states (offered/agreed/in_progress).
                                    // Most likely the rider rejected — reset to requested.
                                    Log.d(TAG, "Poll: no active trip found — treating as rejected")
                                    uiState = WaitingUiState.Rejected
                                }
                                trip.id != tripId -> {
                                    // A different trip became active — should not normally happen here
                                    return@onSuccess
                                }
                                else -> applyStatus(
                                    status             = trip.status,
                                    lastOfferBy        = trip.last_offer_by,
                                    newFare            = trip.offered_fare,
                                    driverCounterCount = trip.driver_counter_count,
                                )
                            }
                        }
                }.onFailure { e ->
                    Log.w(TAG, "Poll error (non-fatal)", e)
                }
            }
        }
    }

    // ── Shared state transition logic ─────────────────────────────────────────
    // Used by both Realtime and polling so both paths behave identically.

    private fun applyStatus(
        status: String,
        lastOfferBy: String?,
        newFare: Double?,
        driverCounterCount: Int,
    ) {
        uiState = when {
            status == "agreed"    -> WaitingUiState.Agreed
            status == "cancelled" -> WaitingUiState.Cancelled
            status == "requested" -> WaitingUiState.Rejected
            status == "offered" && lastOfferBy == "rider" ->
                WaitingUiState.RiderCountered(newFare ?: 0.0, driverCounterCount)
            else -> return // still offered/driver-turn — no change
        }
    }

    // ── Driver-initiated withdraw ─────────────────────────────────────────────

    /**
     * Driver pulls back their own offer before the rider has responded.
     *
     * WithdrawOffer's own server-side UPDATE flips status back to 'requested' —
     * the same signal applyStatus() treats as Rejected (rider declined). Stop
     * polling/Realtime BEFORE setting Withdrawn so that signal can't race in
     * afterwards and show the wrong terminal state/message.
     */
    fun withdrawOffer() {
        if (uiState !is WaitingUiState.Waiting || isWithdrawing) return
        isWithdrawing = true
        viewModelScope.launch {
            repository.withdrawOffer(SessionManager.token, tripId)
                .onSuccess {
                    teardownWatchers()
                    uiState = WaitingUiState.Withdrawn
                }
                .onFailure { e ->
                    // RACE-WITHDRAW fix: a 400 here ("Trip state changed — could not
                    // withdraw") almost always means the rider accepted (or otherwise
                    // changed the trip) in the same instant the driver tapped Withdraw —
                    // the server's atomic UPDATE...WHERE simply lost the race. The trip
                    // itself is fine; only this specific withdraw request failed.
                    //
                    // Instead of stranding the driver on a dead-end error screen, ask the
                    // server what's actually true right now and react to that, mirroring
                    // the same trip == null → Rejected mapping startPolling() already uses.
                    Log.w(TAG, "withdrawOffer failed — reconciling with server state", e)
                    isWithdrawing = false
                    reconcileAfterWithdrawFailure(e.message ?: "Failed to withdraw offer")
                }
        }
    }

    /**
     * Fetches the authoritative trip state after a failed withdraw and maps it
     * to the correct terminal UiState instead of showing a raw error.
     *   - status == "agreed" (rider accepted first) → Agreed, same as normal flow
     *   - no active trip found (rider rejected / reset independently)  → Rejected
     *   - fetch itself fails, or trip is in some other unexpected state → Error,
     *     using the original server message as a fallback
     */
    private suspend fun reconcileAfterWithdrawFailure(originalMessage: String) {
        repository.getActiveTrip(SessionManager.token)
            .onSuccess { trip ->
                when {
                    trip != null && trip.id == tripId && trip.status == "agreed" -> {
                        Log.d(TAG, "Reconcile: rider had already accepted — moving to Agreed")
                        teardownWatchers()
                        uiState = WaitingUiState.Agreed
                    }
                    trip == null -> {
                        Log.d(TAG, "Reconcile: no active trip — treating as rejected")
                        teardownWatchers()
                        uiState = WaitingUiState.Rejected
                    }
                    else -> {
                        Log.w(TAG, "Reconcile: unexpected trip state ${trip.status} — showing error")
                        uiState = WaitingUiState.Error(originalMessage)
                    }
                }
            }
            .onFailure {
                Log.w(TAG, "Reconcile: getActiveTrip also failed — showing original error", it)
                uiState = WaitingUiState.Error(originalMessage)
            }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    /** Stops Realtime + polling. Always call BEFORE setting a terminal uiState
     *  so a late server signal can't race in and overwrite it afterwards. */
    private fun teardownWatchers() {
        pollJob?.cancel()
        pollJob = null
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

    override fun onCleared() {
        super.onCleared()
        teardownWatchers()
    }

    companion object {
        private const val TAG = "WaitingForRiderVM"
    }
}