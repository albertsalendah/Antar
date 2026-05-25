package com.richard_salendah.driverantar.ui.trip

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.driverantar.data.model.DriverTripResponse
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
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

sealed class ActiveTripUiState {
    object Loading   : ActiveTripUiState()
    object Idle      : ActiveTripUiState()
    object Confirming : ActiveTripUiState()  // cancel confirm dialog visible
    object ActionLoading : ActiveTripUiState()
    /** Trip completed — navigate to RateRider */
    object Completed : ActiveTripUiState()
    /** Trip cancelled — navigate back to Map */
    object Cancelled : ActiveTripUiState()
    data class Error(val message: String) : ActiveTripUiState()
}

class ActiveTripViewModel(
    private val repository: DriverRepository,
    val tripId: String
) : ViewModel() {

    var trip    by mutableStateOf<DriverTripResponse?>(null); private set
    var uiState by mutableStateOf<ActiveTripUiState>(ActiveTripUiState.Loading); private set

    private var realtimeChannel: RealtimeChannel? = null

    init {
        loadTrip()
        subscribeToUpdates()
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private fun loadTrip() {
        viewModelScope.launch {
            repository.getActiveTrip(SessionManager.token)
                .onSuccess { t ->
                    trip    = t
                    uiState = ActiveTripUiState.Idle
                }
                .onFailure { e ->
                    uiState = ActiveTripUiState.Error(e.message ?: "Failed to load trip")
                }
        }
    }

    // ── Realtime subscription ─────────────────────────────────────────────────
    // Keeps the trip status in sync if anything changes server-side
    // (e.g. rider cancels, admin intervention).

    private fun subscribeToUpdates() {
        viewModelScope.launch {
            try {
                val client  = SupabaseClientHolder.client
                val channel = client.channel("active-trip-$tripId")
                realtimeChannel = channel

                channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table  = "trips"
                    filter(FilterOperation("id", FilterOperator.EQ, tripId))
                }.onEach { action ->
                    val status = action.record["status"]?.jsonPrimitive?.content ?: return@onEach
                    Log.d(TAG, "Active trip status update → $status")
                    // Reload full trip data when status changes
                    loadTrip()
                }.launchIn(viewModelScope)

                channel.subscribe()
            } catch (e: Exception) {
                Log.w(TAG, "Realtime subscription failed for active trip", e)
                // Non-fatal — driver can still act via buttons
            }
        }
    }

    // ── Trip actions ──────────────────────────────────────────────────────────

    /**
     * Start the trip — driver has arrived at pickup.
     * Only valid when status = agreed.
     */
    fun startTrip() {
        viewModelScope.launch {
            uiState = ActiveTripUiState.ActionLoading
            repository.startTrip(SessionManager.token, tripId)
                .onSuccess { loadTrip() }
                .onFailure { e ->
                    uiState = ActiveTripUiState.Error(
                        e.message ?: "Failed to start trip — please try again"
                    )
                }
        }
    }

    /**
     * Complete the trip — driver has delivered the rider.
     * Only valid when status = in_progress.
     */
    fun completeTrip() {
        viewModelScope.launch {
            uiState = ActiveTripUiState.ActionLoading
            repository.completeTrip(SessionManager.token, tripId)
                .onSuccess { uiState = ActiveTripUiState.Completed }
                .onFailure { e ->
                    uiState = ActiveTripUiState.Error(
                        e.message ?: "Failed to complete trip — please try again"
                    )
                }
        }
    }

    /** Show cancel confirmation dialog */
    fun requestCancel() {
        uiState = ActiveTripUiState.Confirming
    }

    fun dismissCancel() {
        uiState = ActiveTripUiState.Idle
    }

    /**
     * Confirm cancel — only valid when status = agreed (before start).
     * Resets trip back to requested so another driver can pick it up.
     */
    fun confirmCancel() {
        viewModelScope.launch {
            uiState = ActiveTripUiState.ActionLoading
            repository.cancelTrip(SessionManager.token, tripId)
                .onSuccess { uiState = ActiveTripUiState.Cancelled }
                .onFailure { e ->
                    uiState = ActiveTripUiState.Error(
                        e.message ?: "Failed to cancel trip"
                    )
                }
        }
    }

    fun clearError() { uiState = ActiveTripUiState.Idle }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
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

    companion object {
        private const val TAG = "ActiveTripVM"
    }
}