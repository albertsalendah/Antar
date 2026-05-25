package com.richard_salendah.driverantar.ui.trip

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.driverantar.data.model.DriverTripResponse
import com.richard_salendah.driverantar.data.remote.DriverRepository
import com.richard_salendah.driverantar.utils.SessionManager
import kotlinx.coroutines.launch

class TripHistoryViewModel(private val repository: DriverRepository) : ViewModel() {

    companion object {
        private const val PAGE_SIZE = 20
    }

    var trips        by mutableStateOf<List<DriverTripResponse>>(emptyList()); private set
    var isLoading    by mutableStateOf(false);                                  private set
    var isLoadingMore by mutableStateOf(false);                                 private set
    var errorMessage by mutableStateOf<String?>(null);                          private set
    /** False once the server returns fewer items than PAGE_SIZE */
    var hasMore      by mutableStateOf(true);                                   private set

    private var offset = 0

    init { load() }

    // ── Initial / refresh load ─────────────────────────────────────────────

    fun load() {
        viewModelScope.launch {
            isLoading    = true
            errorMessage = null
            offset       = 0
            hasMore      = true

            repository.listTrips(SessionManager.token, limit = PAGE_SIZE, offset = 0)
                .onSuccess { result ->
                    trips   = result
                    offset  = result.size
                    hasMore = result.size == PAGE_SIZE
                }
                .onFailure { e -> errorMessage = e.message ?: "Failed to load trip history" }

            isLoading = false
        }
    }

    // ── Load next page (called when user scrolls near the bottom) ─────────

    fun loadMore() {
        if (isLoadingMore || !hasMore || isLoading) return
        viewModelScope.launch {
            isLoadingMore = true

            repository.listTrips(SessionManager.token, limit = PAGE_SIZE, offset = offset)
                .onSuccess { result ->
                    trips   = trips + result
                    offset += result.size
                    hasMore = result.size == PAGE_SIZE
                }
                .onFailure { /* silent — user can pull-to-refresh to retry */ }

            isLoadingMore = false
        }
    }
}