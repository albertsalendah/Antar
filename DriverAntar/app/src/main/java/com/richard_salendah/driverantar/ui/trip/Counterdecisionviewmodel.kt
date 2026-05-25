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

    /** Driver's counter fare — starts at their last offered price (riderFare + step as suggestion) */
    var counterFare by mutableStateOf(
        // Round up to nearest 1000 above rider's offer as a starting suggestion
        riderFare + STEP
    ); private set

    var state by mutableStateOf<CounterDecisionState>(CounterDecisionState.Idle); private set

    /** Driver still has counter attempts remaining */
    val canCounter: Boolean get() = driverCounterCount < maxDriverCounters
    val isBelowFloor: Boolean get() = counterFare < defaultFare
    val canDecrement: Boolean get() = counterFare - STEP >= defaultFare

    fun increment() { counterFare += STEP }
    fun decrement() { if (canDecrement) counterFare -= STEP }
    fun setFare(value: Double) { counterFare = value }

    // ── Actions ───────────────────────────────────────────────────────────────

    /**
     * Driver accepts the rider's counter-offer price.
     * Server: POST /trips/:id/accept would be for riders — here we use a
     * special accept path. Actually the driver accepting the rider's counter
     * is done by posting a counter equal to riderFare, which the server
     * interprets as agreement when last_offer_by becomes driver and the price
     * matches. In practice the server sets status=agreed on AcceptOffer.
     *
     * The correct server call for the driver accepting is:
     *   Not a dedicated endpoint — driver posts counter = riderFare (matching price),
     *   which in the negotiation model means agreement from the driver's side.
     *   The server's CounterOffer handler updates last_offer_by=driver.
     *   Then the rider must accept one final time.
     *
     * HOWEVER — looking at the server handler again, there's no driver-accept
     * endpoint distinct from counter. The simplest correct flow is:
     *   Driver counter with riderFare amount = effectively accepting their price.
     *   Rider then sees last_offer_by=driver and accepts.
     *
     * Simpler UX alternative implemented here:
     *   "Accept" button posts counterFare = riderFare to /counter.
     *   This signals to the rider that the driver matches their price.
     */
    fun acceptRiderOffer() {
        viewModelScope.launch {
            state = CounterDecisionState.Loading
            // Post the rider's fare as our counter (signals agreement at that price)
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
                .onFailure { e -> state = CounterDecisionState.Error(e.message ?: "Failed to counter") }
        }
    }

    /**
     * Driver rejects the rider's counter-offer.
     * Server resets the trip to 'requested' so another driver can offer fresh.
     * Client navigates back to IncomingTrips.
     */
    fun rejectAndReset() {
        viewModelScope.launch {
            state = CounterDecisionState.Loading
            repository.cancelTrip(SessionManager.token, tripId)
                .onSuccess { state = CounterDecisionState.Rejected }
                .onFailure { e -> state = CounterDecisionState.Error(e.message ?: "Failed to reject") }
        }
    }

    fun clearError() { state = CounterDecisionState.Idle }
}