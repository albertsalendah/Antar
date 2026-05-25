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
    /** Non-null when the driver just submitted an offer and we're waiting for a response */
    var snackMessage by mutableStateOf<String?>(null);                           private set

    private var pollJob: Job? = null

    // ── Polling ───────────────────────────────────────────────────────────────

    /**
     * Starts polling GET /driver/trips/incoming every 5 seconds.
     * Call this from LaunchedEffect(Unit) in IncomingTripsScreen.
     * The coroutine is tied to viewModelScope so it stops automatically
     * when the ViewModel is cleared (screen leaves the back stack).
     */
    fun startPolling() {
        if (pollJob?.isActive == true) return   // already polling
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

    /** Manual pull-to-refresh — always shows spinner */
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
                // Don't overwrite an existing list with an error on background poll
                if (trips.isEmpty()) errorMessage = e.message ?: "Failed to load trips"
            }
        if (showLoadingSpinner) isLoading = false
    }

    // ── Snackbar ──────────────────────────────────────────────────────────────

    fun showSnack(message: String) { snackMessage = message }
    fun clearSnack()               { snackMessage = null    }
}