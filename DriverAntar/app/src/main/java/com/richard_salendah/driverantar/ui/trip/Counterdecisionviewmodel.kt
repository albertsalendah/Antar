package com.richard_salendah.driverantar.ui.trip

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.driverantar.data.remote.DriverRepository
import com.richard_salendah.driverantar.utils.SessionManager
import kotlinx.coroutines.launch

sealed class CounterDecisionState {
    object Idle    : CounterDecisionState()
    object Loading : CounterDecisionState()
    /** Accepted — driver agreed to rider's price → move to ActiveTrip */
    object Accepted : CounterDecisionState()
    /** Driver sent a counter → move back to WaitingForRider */
    data class Countered(val newFare: Double) : CounterDecisionState()
    /** Driver rejected → trip resets to requested → back to IncomingTrips */
    object Rejected : CounterDecisionState()
    data class Error(val message: String) : CounterDecisionState()
}

class CounterDecisionViewModel(
    private val repository: DriverRepository,
    val tripId: String,
    val riderFare: Double,
    val defaultFare: Double,
    val driverCounterCount: Int,
    val maxDriverCounters: Int
) : ViewModel() {

    companion object {
        private const val STEP = 1_000.0
    }

    var counterFare by mutableStateOf(riderFare + STEP); private set
    var state by mutableStateOf<CounterDecisionState>(CounterDecisionState.Idle); private set

    val canCounter: Boolean get() = driverCounterCount < maxDriverCounters
    val isBelowFloor: Boolean get() = counterFare < defaultFare
    val canDecrement: Boolean get() = counterFare - STEP >= defaultFare

    fun increment() { counterFare += STEP }
    fun decrement() { if (canDecrement) counterFare -= STEP }
    fun setFare(value: Double) { counterFare = value }

    // ── Actions ───────────────────────────────────────────────────────────────

    /**
     * Driver matches rider's price by counter-offering the same amount.
     * Server sets last_offer_by='driver'; rider then accepts one final time.
     */
    fun acceptRiderOffer() {
        viewModelScope.launch {
            state = CounterDecisionState.Loading
            repository.counterOffer(SessionManager.token, tripId, riderFare)
                .onSuccess { state = CounterDecisionState.Countered(riderFare) }
                .onFailure { e -> state = CounterDecisionState.Error(e.message ?: "Failed") }
        }
    }

    fun submitCounter() {
        if (!canCounter || isBelowFloor || state is CounterDecisionState.Loading) return
        viewModelScope.launch {
            state = CounterDecisionState.Loading
            repository.counterOffer(SessionManager.token, tripId, counterFare)
                .onSuccess { state = CounterDecisionState.Countered(counterFare) }
                .onFailure { e ->
                    state = CounterDecisionState.Error(e.message ?: "Failed to counter")
                }
        }
    }

    /**
     * Driver rejects the rider's counter-offer.
     * Calls WithdrawOffer server-side which resets the trip to 'requested'.
     *
     * RACE-WITHDRAW fix: mirrors the same fix applied to WaitingForRiderViewModel.
     * If withdrawOffer returns a 400 ("Trip state changed"), the rider most likely
     * accepted the driver's previous counter-offer in the same instant — the server's
     * atomic UPDATE...WHERE lost the race. The trip is already 'agreed'.
     *
     * On failure: fetch the authoritative trip state and react accordingly:
     *   - agreed  → Accepted (navigate to WaitingForRider, which immediately sees Agreed)
     *   - no active trip / requested / cancelled → Rejected (back to IncomingTrips)
     *   - fetch fails or unexpected state → Error (original message, unchanged behaviour)
     */
    fun rejectAndReset() {
        viewModelScope.launch {
            state = CounterDecisionState.Loading
            repository.withdrawOffer(SessionManager.token, tripId)
                .onSuccess { state = CounterDecisionState.Rejected }
                .onFailure { e ->
                    reconcileAfterWithdrawFailure(e.message ?: "Failed to reject")
                }
        }
    }

    private suspend fun reconcileAfterWithdrawFailure(originalMessage: String) {
        repository.getActiveTrip(SessionManager.token)
            .onSuccess { trip ->
                state = when {
                    trip != null && trip.id == tripId && trip.status == "agreed" -> {
                        // Rider had already accepted the driver's last counter-offer.
                        // Move forward to WaitingForRider, which will immediately
                        // detect Agreed via its own poll/Realtime and go to ActiveTrip.
                        CounterDecisionState.Accepted
                    }
                    trip == null || trip.id != tripId -> {
                        // No active trip → trip was reset or cancelled independently.
                        CounterDecisionState.Rejected
                    }
                    trip.status == "requested" || trip.status == "cancelled" -> {
                        // Withdraw partially succeeded in resetting the trip.
                        CounterDecisionState.Rejected
                    }
                    else -> {
                        // Unexpected state — fall back to the original error message.
                        CounterDecisionState.Error(originalMessage)
                    }
                }
            }
            .onFailure {
                // getActiveTrip itself failed (network down etc.) — show original error.
                state = CounterDecisionState.Error(originalMessage)
            }
    }

    fun clearError() { state = CounterDecisionState.Idle }
}