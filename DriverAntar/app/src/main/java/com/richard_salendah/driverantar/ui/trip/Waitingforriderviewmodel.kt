package com.richard_salendah.driverantar.ui.trip

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.driverantar.data.remote.DriverRepository
import com.richard_salendah.driverantar.ui.supabase.SupabaseClientHolder
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

    private var realtimeChannel: RealtimeChannel? = null

    init { subscribeToTripChanges() }

    private fun subscribeToTripChanges() {
        viewModelScope.launch {
            try {
                val client = SupabaseClientHolder.client

                // Use a timestamp suffix to guarantee a unique channel name on every
                // VM creation. Without this, navigating CounterDecision → WaitingForRider
                // creates a channel with the same name as the previous VM's channel
                // before onCleared() async cleanup finishes, causing the new subscription
                // to silently fail and leaving the driver stuck on this screen.
                val channel = client.channel("trip-watch-$tripId-${System.currentTimeMillis()}")
                realtimeChannel = channel

                channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "trips"
                    filter(FilterOperation("id", FilterOperator.EQ, tripId))
                }.onEach { action ->
                    handleUpdate(action.record)
                }.launchIn(viewModelScope)

                channel.subscribe()
                Log.d(TAG, "Subscribed to trip $tripId")

            } catch (e: Exception) {
                Log.e(TAG, "Realtime subscription failed", e)
                uiState = WaitingUiState.Error(
                    "Could not connect to real-time updates. Check your connection."
                )
            }
        }
    }

    private fun handleUpdate(
        record: Map<String, kotlinx.serialization.json.JsonElement>
    ) {
        val status             = record["status"]?.jsonPrimitive?.content          ?: return
        val lastOfferBy        = record["last_offer_by"]?.jsonPrimitive?.content
        val newFare            = record["offered_fare"]?.jsonPrimitive?.content?.toDoubleOrNull()
        // Carry the actual counter count from the DB row so CounterDecision
        // shows the correct remaining attempts instead of always starting at 0.
        val driverCounterCount = record["driver_counter_count"]?.jsonPrimitive?.content
            ?.toIntOrNull() ?: 0

        Log.d(TAG, "Trip update → status=$status last_offer_by=$lastOfferBy")

        uiState = when {
            status == "agreed"    -> WaitingUiState.Agreed
            status == "cancelled" -> WaitingUiState.Cancelled
            status == "requested" -> WaitingUiState.Rejected
            status == "offered" && lastOfferBy == "rider" ->
                WaitingUiState.RiderCountered(newFare ?: 0.0, driverCounterCount)
            else -> WaitingUiState.Waiting
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            try {
                realtimeChannel?.let { ch ->
                    ch.unsubscribe()
                    SupabaseClientHolder.client.realtime.removeChannel(ch)
                    Log.d(TAG, "Unsubscribed channel for trip $tripId")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up Realtime channel", e)
            }
        }
    }

    companion object {
        private const val TAG = "WaitingForRiderVM"
    }
}