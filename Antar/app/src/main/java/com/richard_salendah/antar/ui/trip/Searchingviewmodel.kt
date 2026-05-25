package com.richard_salendah.antar.ui.trip

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.antar.Antar
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

class SearchingViewModel(app: Application) : AndroidViewModel(app) {

    private val api      = (app as Antar).apiService
    private val supabase = (app as Antar).supabase

    var tripStatus    by mutableStateOf("requested")
    var cancelLoading by mutableStateOf(false)
    var cancelError   by mutableStateOf<String?>(null)

    private var channel: RealtimeChannel? = null
    private var pollJob: Job?             = null
    private var started                   = false

    fun startWatching(
        tripId: String,
        onOfferReceived: () -> Unit,
        onTripCancelled: () -> Unit,
    ) {
        if (started) return
        started = true
        subscribeRealtime(tripId, onOfferReceived, onTripCancelled)
        startPolling(tripId, onOfferReceived, onTripCancelled)
    }

    private fun subscribeRealtime(
        tripId: String,
        onOfferReceived: () -> Unit,
        onTripCancelled: () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                val ch = supabase.channel("trip_search_$tripId")
                channel = ch

                // ▼ Fix: use filter() function instead of filter = "..." property
                ch.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "trips"
                    filter("id", FilterOperator.EQ, tripId)
                }.onEach { action ->
                    val newStatus = action.record["status"]
                        ?.jsonPrimitive?.content ?: return@onEach
                    handleStatus(newStatus, onOfferReceived, onTripCancelled)
                }.launchIn(viewModelScope)

                ch.subscribe()
            } catch (_: Exception) {
                // Realtime unavailable — polling fallback handles it
            }
        }
    }

    private fun startPolling(
        tripId: String,
        onOfferReceived: () -> Unit,
        onTripCancelled: () -> Unit,
    ) {
        pollJob = viewModelScope.launch {
            while (true) {
                delay(5_000L)
                runCatching {
                    val resp = api.getTrip(tripId)
                    if (resp.isSuccessful) {
                        val status = resp.body()?.data?.status ?: return@runCatching
                        handleStatus(status, onOfferReceived, onTripCancelled)
                    }
                }
            }
        }
    }

    private fun handleStatus(
        status: String,
        onOfferReceived: () -> Unit,
        onTripCancelled: () -> Unit,
    ) {
        tripStatus = status
        when (status) {
            "offered"   -> { stopWatching(); onOfferReceived() }
            "cancelled" -> { stopWatching(); onTripCancelled() }
        }
    }

    fun cancelTrip(tripId: String, onCancelled: () -> Unit) {
        viewModelScope.launch {
            cancelLoading = true
            cancelError   = null
            runCatching {
                val resp = api.cancelTrip(tripId)
                if (resp.isSuccessful) { stopWatching(); onCancelled() }
                else cancelError = "Gagal membatalkan pesanan, coba lagi"
            }.onFailure { cancelError = "Tidak dapat terhubung ke server" }
            cancelLoading = false
        }
    }

    private fun stopWatching() {
        pollJob?.cancel()
        viewModelScope.launch {
            runCatching { channel?.let { supabase.realtime.removeChannel(it) } }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopWatching()
    }
}