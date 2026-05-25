package com.richard_salendah.driverantar.ui.trip

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.driverantar.data.remote.DriverRepository
import com.richard_salendah.driverantar.utils.SessionManager
import kotlinx.coroutines.launch

sealed class OfferUiState {
    object Idle       : OfferUiState()
    object Loading    : OfferUiState()
    object Success    : OfferUiState()   // navigate to WaitingForRider
    data class Error(val message: String) : OfferUiState()
}

/**
 * @param tripId      UUID of the trip being offered on
 * @param defaultFare Floor price set by admin — driver cannot go below this
 *                    for BOTH transport and errand (per updated rules)
 * @param tripType    "transport" | "errand"
 */
class OfferPriceViewModel(
    private val repository: DriverRepository,
    val tripId: String,
    val defaultFare: Double,
    val tripType: String
) : ViewModel() {

    companion object {
        private const val STEP = 1_000.0   // +/- button step in IDR
    }

    /**
     * Current fare the driver intends to offer.
     * Pre-filled with defaultFare so the driver starts at the minimum.
     */
    var currentFare by mutableStateOf(defaultFare); private set

    var uiState by mutableStateOf<OfferUiState>(OfferUiState.Idle); private set

    // ── Fare input ────────────────────────────────────────────────────────────

    fun increment() {
        currentFare += STEP
    }

    fun decrement() {
        val next = currentFare - STEP
        // Never go below the floor — button is disabled in UI but guard here too
        if (next >= defaultFare) currentFare = next
    }

    /** Direct text field edit — clamp to floor on focus-loss, not during typing */
    fun setFare(value: Double) {
        currentFare = value
    }

    /**
     * True when the driver's fare is below the floor.
     * Both transport AND errand enforce the floor price.
     * The UI shows a warning and disables submit when this is true.
     */
    val isBelowFloor: Boolean get() = currentFare < defaultFare

    val canDecrement: Boolean get() = currentFare - STEP >= defaultFare

    // ── Submit ────────────────────────────────────────────────────────────────

    fun submitOffer() {
        if (isBelowFloor) return                          // safety guard
        if (uiState is OfferUiState.Loading) return       // prevent double-tap

        viewModelScope.launch {
            uiState = OfferUiState.Loading
            repository.offerPrice(SessionManager.token, tripId, currentFare)
                .onSuccess { uiState = OfferUiState.Success }
                .onFailure { e ->
                    uiState = OfferUiState.Error(
                        e.message ?: "Failed to submit offer — the trip may no longer be available"
                    )
                }
        }
    }

    fun clearError() { uiState = OfferUiState.Idle }
}