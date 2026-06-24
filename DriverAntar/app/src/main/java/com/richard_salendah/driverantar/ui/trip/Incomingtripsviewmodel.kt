package com.richard_salendah.driverantar.ui.trip

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.driverantar.data.model.IncomingTripResponse
import com.richard_salendah.driverantar.data.remote.DriverRepository
import com.richard_salendah.driverantar.utils.SessionManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class IncomingTripsViewModel(private val repository: DriverRepository) : ViewModel() {

    var trips        by mutableStateOf<List<IncomingTripResponse>>(emptyList()); private set
    var isLoading    by mutableStateOf(false);                                   private set
    var errorMessage by mutableStateOf<String?>(null);                           private set
    var snackMessage by mutableStateOf<String?>(null);                           private set
    /**
     * Non-null while a decline request is in flight for a specific trip.
     * Used by TripCard to show a loading indicator and disable both buttons.
     * Only one trip can be declining at a time.
     */
    var decliningTripId by mutableStateOf<String?>(null);                        private set

    private var pollJob: Job? = null

    // ── Polling ───────────────────────────────────────────────────────────────

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                fetchTrips(showLoadingSpinner = trips.isEmpty())
                delay(5_000L)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refresh() {
        viewModelScope.launch { fetchTrips(showLoadingSpinner = true) }
    }

    private suspend fun fetchTrips(showLoadingSpinner: Boolean) {
        if (showLoadingSpinner) isLoading = true
        repository.getIncomingTrips(SessionManager.token)
            .onSuccess { result ->
                trips        = result
                errorMessage = null
            }
            .onFailure { e ->
                if (trips.isEmpty()) errorMessage = e.message ?: "Failed to load trips"
            }
        if (showLoadingSpinner) isLoading = false
    }

    // ── Decline candidate ─────────────────────────────────────────────────────

    /**
     * Driver declines a trip they are the approved candidate for, before offering.
     * Server immediately excludes the driver, finds the next candidate, and notifies
     * the rider via FCM + Realtime. On success the card is removed locally without
     * waiting for the next poll.
     *
     * Guards against concurrent declines with [decliningTripId] — only one in-flight
     * request allowed at a time.
     */
    fun declineTrip(tripId: String) {
        if (decliningTripId != null) return
        decliningTripId = tripId
        viewModelScope.launch {
            repository.declineCandidate(SessionManager.token, tripId)
                .onSuccess {
                    // Remove immediately so the driver doesn't see the card again
                    // before the next poll drops it (server already excluded them).
                    trips = trips.filter { it.id != tripId }
                }
                .onFailure { e ->
                    showSnack(e.message ?: "Gagal menolak trip — coba lagi")
                }
            decliningTripId = null
        }
    }

    // ── Snackbar ──────────────────────────────────────────────────────────────

    fun showSnack(message: String) { snackMessage = message }
    fun clearSnack()               { snackMessage = null    }
}