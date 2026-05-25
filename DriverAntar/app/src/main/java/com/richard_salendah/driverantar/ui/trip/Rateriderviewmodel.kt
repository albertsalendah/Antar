package com.richard_salendah.driverantar.ui.trip

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.driverantar.data.remote.DriverRepository
import com.richard_salendah.driverantar.utils.SessionManager
import kotlinx.coroutines.launch

sealed class RateRiderUiState {
    object Idle        : RateRiderUiState()
    object Loading     : RateRiderUiState()
    object Done        : RateRiderUiState()   // submitted or skipped → navigate to Map
    data class Error(val message: String) : RateRiderUiState()
}

class RateRiderViewModel(
    private val repository: DriverRepository,
    val tripId: String
) : ViewModel() {

    /** 0 = nothing selected yet. Valid submission range: 1–5. */
    var selectedScore by mutableIntStateOf(0); private set
    var comment       by mutableStateOf("");   private set
    var uiState       by mutableStateOf<RateRiderUiState>(RateRiderUiState.Idle); private set

    val canSubmit: Boolean get() = selectedScore in 1..5

    fun selectScore(score: Int) {
        if (score in 1..5) selectedScore = score
    }

    fun settComment(text: String) {
        comment = text
    }

    fun submitRating() {
        if (!canSubmit || uiState is RateRiderUiState.Loading) return
        viewModelScope.launch {
            uiState = RateRiderUiState.Loading
            repository.rateRider(
                token   = SessionManager.token,
                tripId  = tripId,
                score   = selectedScore,
                comment = comment.trim()
            )
                .onSuccess { uiState = RateRiderUiState.Done }
                .onFailure { e ->
                    // Unique constraint means they already rated — treat as done
                    if (e.message?.contains("already rated") == true ||
                        e.message?.contains("unique") == true) {
                        uiState = RateRiderUiState.Done
                    } else {
                        uiState = RateRiderUiState.Error(e.message ?: "Failed to submit rating")
                    }
                }
        }
    }

    /** Skip without rating — navigates to Map same as after submit */
    fun skip() { uiState = RateRiderUiState.Done }

    fun clearError() { uiState = RateRiderUiState.Idle }
}