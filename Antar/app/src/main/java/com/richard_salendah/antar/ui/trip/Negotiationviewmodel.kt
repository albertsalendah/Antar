package com.richard_salendah.antar.ui.trip

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.antar.Antar
import com.richard_salendah.antar.data.model.CounterOfferRequest
import com.richard_salendah.antar.data.model.TripResponse
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

class NegotiationViewModel(app: Application) : AndroidViewModel(app) {

    private val api      = (app as Antar).apiService
    private val supabase = (app as Antar).supabase

    var trip          by mutableStateOf<TripResponse?>(null)
    var loading       by mutableStateOf(false)
    var actionLoading by mutableStateOf(false)
    var error         by mutableStateOf<String?>(null)

    var counterInput  by mutableStateOf("")
    var showCounter   by mutableStateOf(false)

    var counterExhausted by mutableStateOf(false)
    private var channel: RealtimeChannel? = null
    private var pollJob: Job?             = null

    // Internal — exposed so NegotiationScreen retry button can reset it
    var started = false

    fun start(
        tripId: String,
        onAgreed: () -> Unit,
        onReset: () -> Unit,
    ) {
        if (started) return
        started = true
        loadTrip(tripId)
        subscribeRealtime(tripId, onAgreed, onReset)
        startPolling(tripId, onAgreed, onReset)
    }

    private fun loadTrip(tripId: String) {
        viewModelScope.launch {
            loading = true
            error   = null
            runCatching {
                val resp = api.getTrip(tripId)
                if (resp.isSuccessful) {
                    trip = resp.body()?.data
                    if (trip == null) error = "Data penawaran tidak ditemukan"
                } else {
                    error = "Gagal memuat detail penawaran (${resp.code()})"
                }
            }.onFailure {
                error = "Tidak dapat terhubung ke server"
            }
            loading = false
        }
    }

    private fun subscribeRealtime(tripId: String, onAgreed: () -> Unit, onReset: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                val ch = supabase.channel("trip_negotiation_$tripId")
                channel = ch
                ch.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "trips"
                    filter("id", FilterOperator.EQ, tripId)
                }.onEach { action ->
                    val statusRaw = action.record["status"]?.jsonPrimitive?.content
                    refreshAndHandle(tripId, statusRaw, onAgreed, onReset)
                }.launchIn(viewModelScope)
                ch.subscribe()
            }
        }
    }

    private fun startPolling(tripId: String, onAgreed: () -> Unit, onReset: () -> Unit) {
        pollJob = viewModelScope.launch {
            while (true) {
                delay(5_000L)
                runCatching {
                    val resp = api.getTrip(tripId)
                    if (resp.isSuccessful) {
                        val t = resp.body()?.data ?: return@runCatching
                        trip = t
                        handleStatus(t.status, onAgreed, onReset)
                    }
                }
            }
        }
    }

    private fun refreshAndHandle(
        tripId: String,
        statusHint: String?,
        onAgreed: () -> Unit,
        onReset: () -> Unit,
    ) {
        viewModelScope.launch {
            runCatching {
                val resp = api.getTrip(tripId)
                if (resp.isSuccessful) {
                    val t = resp.body()?.data ?: return@runCatching
                    trip = t
                    handleStatus(t.status, onAgreed, onReset)
                }
            }
        }
    }

    private fun handleStatus(status: String, onAgreed: () -> Unit, onReset: () -> Unit) {
        when (status) {
            "agreed"    -> { stopWatching(); onAgreed() }
            "requested" -> { stopWatching(); onReset()  }
            "cancelled" -> { stopWatching(); onReset()  }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun accept(tripId: String, onAgreed: () -> Unit) {
        viewModelScope.launch {
            actionLoading = true
            error         = null
            runCatching {
                val resp = api.acceptOffer(tripId)
                if (resp.isSuccessful) { stopWatching(); onAgreed() }
                else error = "Gagal menerima penawaran, coba lagi"
            }.onFailure { error = "Tidak dapat terhubung ke server" }
            actionLoading = false
        }
    }

    fun reject(tripId: String, onReset: () -> Unit) {
        viewModelScope.launch {
            actionLoading = true
            error         = null
            runCatching {
                val resp = api.rejectOffer(tripId)
                if (resp.isSuccessful) { stopWatching(); onReset() }
                else error = "Gagal menolak penawaran, coba lagi"
            }.onFailure { error = "Tidak dapat terhubung ke server" }
            actionLoading = false
        }
    }

    fun submitCounter(tripId: String) {
        val fare = counterInput.trim().toDoubleOrNull()
        if (fare == null || fare <= 0) { error = "Masukkan nominal yang valid"; return }
        viewModelScope.launch {
            actionLoading = true
            error         = null
            runCatching {
                val resp = api.counterOffer(tripId, CounterOfferRequest(fare))
                if (resp.isSuccessful) {
                    showCounter  = false
                    counterInput = ""
                    val t = api.getTrip(tripId)
                    if (t.isSuccessful) trip = t.body()?.data
                } else {
                    val msg = parseError(resp.errorBody()?.string()) ?: "Gagal menawar, coba lagi"
                    error = msg
                    if (msg.contains("attempts") || msg.contains("Anda telah")) counterExhausted = true
                }
            }.onFailure { error = "Tidak dapat terhubung ke server" }
            actionLoading = false
        }
    }

    private fun stopWatching() {
        pollJob?.cancel()
        viewModelScope.launch {
            runCatching { channel?.let { supabase.realtime.removeChannel(it) } }
        }
    }

    private fun parseError(body: String?): String? = runCatching {
        org.json.JSONObject(body ?: "").optString("error").takeIf { it.isNotEmpty() }
    }.getOrNull()

    override fun onCleared() { super.onCleared(); stopWatching() }
}