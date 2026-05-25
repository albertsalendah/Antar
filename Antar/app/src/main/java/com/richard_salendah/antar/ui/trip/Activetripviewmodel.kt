package com.richard_salendah.antar.ui.trip

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.antar.Antar
import com.richard_salendah.antar.data.model.TripResponse
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

class ActiveTripViewModel(app: Application) : AndroidViewModel(app) {

    private val api      = (app as Antar).apiService
    private val supabase = (app as Antar).supabase

    var trip    by mutableStateOf<TripResponse?>(null)
    var loading by mutableStateOf(false)
    var error   by mutableStateOf<String?>(null)

    // ── Location tracker (Option A active, Option B ready) ────────────────────
    // To migrate to Option B: replace PollingLocationTracker with
    // RealtimeLocationTracker once the stub is implemented.
    private val locationTracker: LocationTracker = PollingLocationTracker(api, intervalMs = 3_000L)

    /** Exposes driver location to the Screen — source-agnostic. */
    val driverLocation: StateFlow<DriverLocation?> = locationTracker.location

    // ── Trip status (Realtime + polling fallback) ──────────────────────────────
    private var channel:    RealtimeChannel? = null
    private var statusPoll: Job?             = null
    private var started                      = false

    fun start(tripId: String, onCompleted: () -> Unit) {
        if (started) return
        started = true
        loadTrip(tripId)
        locationTracker.start(tripId, viewModelScope)
        subscribeRealtime(tripId, onCompleted)
        startStatusPolling(tripId, onCompleted)
    }

    private fun loadTrip(tripId: String) {
        viewModelScope.launch {
            loading = true
            runCatching {
                val resp = api.getTrip(tripId)
                if (resp.isSuccessful) trip = resp.body()?.data
                else error = "Gagal memuat detail perjalanan"
            }.onFailure { error = "Tidak dapat terhubung ke server" }
            loading = false
        }
    }

    // ── Realtime — instant status push ────────────────────────────────────────
    private fun subscribeRealtime(tripId: String, onCompleted: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                val ch = supabase.channel("active_trip_$tripId")
                channel = ch
                ch.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "trips"
                    filter("id", FilterOperator.EQ, tripId)
                }.onEach { action ->
                    val status = action.record["status"]?.jsonPrimitive?.content
                    // Refresh full trip from API so the UI gets updated coords too
                    val resp = api.getTrip(tripId)
                    if (resp.isSuccessful) {
                        val t = resp.body()?.data ?: return@onEach
                        trip = t
                        if (t.status == "completed") { teardown(); onCompleted() }
                    } else if (status == "completed") {
                        teardown(); onCompleted()
                    }
                }.launchIn(viewModelScope)
                ch.subscribe()
            }
        }
    }

    // ── Status polling fallback (longer interval — location has its own 3s poll)
    private fun startStatusPolling(tripId: String, onCompleted: () -> Unit) {
        statusPoll = viewModelScope.launch {
            while (true) {
                delay(6_000L)
                runCatching {
                    val resp = api.getTrip(tripId)
                    if (resp.isSuccessful) {
                        val t = resp.body()?.data ?: return@runCatching
                        trip = t
                        if (t.status == "completed") { teardown(); onCompleted() }
                    }
                }
            }
        }
    }

    private fun teardown() {
        locationTracker.stop()
        statusPoll?.cancel()
        viewModelScope.launch {
            runCatching { channel?.let { supabase.realtime.removeChannel(it) } }
        }
    }

    override fun onCleared() { super.onCleared(); teardown() }
}