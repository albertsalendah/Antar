package com.richard_salendah.antar.ui.trip

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.antar.Antar
import com.richard_salendah.antar.data.model.ApproveCandidateRequest
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

    companion object {
        private const val STEP = 1_000.0
    }

    var trip             by mutableStateOf<TripResponse?>(null)
    var loading          by mutableStateOf(false)
    var error            by mutableStateOf<String?>(null)
    var actionLoading    by mutableStateOf(false)
    var showCounter      by mutableStateOf(false)
    var counterExhausted by mutableStateOf(false)
    var showRejectDialog by mutableStateOf(false)

    // ── Withdrew dialog state [R2, R3, R4, R5, R6] ────────────────────────────
    // Shown when the driver withdraws their price offer (trip resets to requested).
    // The next candidate driver is NOT notified until rider taps Continue and
    // approve-candidate is called — this is what "pauses the search" [R3].
    var showWithdrewDialog     by mutableStateOf(false)
    var pendingNextCandidateId by mutableStateOf<String?>(null); private set

    // dialogTriggered prevents Realtime and polling from both firing the dialog
    // simultaneously when they detect status='requested' at the same moment.
    private var dialogTriggered  = false
    private var currentTripId    = ""

    var counterFare by mutableStateOf(0.0); private set

    private var channel: RealtimeChannel? = null
    private var pollJob: Job?             = null
    var started = false

    // ── Stepper helpers ───────────────────────────────────────────────────────

    fun openCounter() { counterFare = trip?.offeredFare ?: 0.0; showCounter = true }
    fun incrementCounter() { counterFare += STEP }
    fun decrementCounter() { val next = counterFare - STEP; if (next > 0) counterFare = next }

    @JvmName("updateCounterFare")
    fun setCounterFare(value: Double) { counterFare = value }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * @param initialReason Pass "withdrew" when navigating here via a background FCM tap
     *   after a driver withdrawal — pre-arms the dialog so it shows once the trip loads.
     *   Leave empty ("") for all normal navigation paths.
     */
    fun start(
        tripId: String,
        initialReason: String = "",
        onAgreed: () -> Unit,
        onReset: () -> Unit,
    ) {
        if (started) return
        currentTripId = tripId
        started       = true
        // Pre-arm when arriving via FCM background tap so the Realtime update that
        // arrives shortly after can't double-trigger the dialog.
        if (initialReason == "withdrew") dialogTriggered = true
        loadTrip(tripId, showDialogOnLoad = initialReason == "withdrew")
        subscribeRealtime(tripId, onAgreed, onReset)
        startPolling(tripId, onAgreed, onReset)
    }

    private fun loadTrip(tripId: String, showDialogOnLoad: Boolean = false) {
        viewModelScope.launch {
            loading = true
            error   = null
            runCatching {
                val resp = api.getTrip(tripId)
                if (resp.isSuccessful) {
                    val t = resp.body()?.data
                    trip = t
                    when {
                        t == null -> error = "Data penawaran tidak ditemukan"
                        // Background FCM tap: trip already in requested state, show dialog.
                        showDialogOnLoad && t.status == "requested" -> {
                            pendingNextCandidateId = t.candidateDriverId
                            showWithdrewDialog = true
                        }
                    }
                } else {
                    error = "Gagal memuat detail penawaran (${resp.code()})"
                }
            }.onFailure { error = "Tidak dapat terhubung ke server" }
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

    /**
     * "requested" = driver withdrew. Show dialog instead of immediately navigating away.
     * Polling and Realtime keep running to catch agreed/cancelled transitions while
     * the rider is reading the dialog.
     * "cancelled" = direct onReset (no dialog needed — trip is dead).
     */
    private fun handleStatus(status: String, onAgreed: () -> Unit, onReset: () -> Unit) {
        when (status) {
            "agreed" -> { stopWatching(); onAgreed() }
            "requested" -> {
                if (dialogTriggered) return
                dialogTriggered = true
                viewModelScope.launch {
                    // Fetch fresh to get the updated candidateDriverId (next candidate
                    // assigned by WithdrawOffer, still unapproved, not yet notified [R3]).
                    val resp = api.getTrip(currentTripId)
                    if (resp.isSuccessful) {
                        pendingNextCandidateId = resp.body()?.data?.candidateDriverId
                    }
                    showWithdrewDialog = true
                }
            }
            "cancelled" -> { stopWatching(); onReset() }
        }
    }

    // ── Withdrew dialog actions ───────────────────────────────────────────────

    /**
     * Rider chooses to continue searching [R4].
     *
     * If a next candidate exists: calls approve-candidate — this is the moment
     * the next driver gets notified [R3] — then navigates to CandidateReview.
     * If no next candidate: navigates to NoDriverFound directly [R5, R6].
     */
    fun continueSearch(
        onNavigateToCandidateReview: () -> Unit,
        onNoDriverFound: () -> Unit,
    ) {
        showWithdrewDialog = false
        dialogTriggered    = false
        val nextId = pendingNextCandidateId
        if (nextId == null) {
            stopWatching()
            onNoDriverFound()
            return
        }
        viewModelScope.launch {
            actionLoading = true
            runCatching {
                val resp = api.approveCandidate(currentTripId, ApproveCandidateRequest(nextId))
                stopWatching()
                if (resp.isSuccessful) {
                    onNavigateToCandidateReview()
                } else {
                    // Candidate no longer available — send to NoDriverFound list
                    onNoDriverFound()
                }
            }.onFailure {
                stopWatching()
                onNoDriverFound()
            }
            actionLoading = false
        }
    }

    /**
     * Rider chooses to stop automatic searching [R5].
     * Trip stays 'requested'; rider navigates to NoDriverFound to pick manually
     * or cancel from there. No API call — approve-candidate is never called [R3].
     */
    fun stopSearch(onNoDriverFound: () -> Unit) {
        showWithdrewDialog = false
        onNoDriverFound()
    }

    // ── Existing negotiation actions ──────────────────────────────────────────

    fun accept(tripId: String, onAgreed: () -> Unit) {
        if (actionLoading) return
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
        if (actionLoading) return
        showRejectDialog = false
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
        if (counterFare <= 0) { error = "Masukkan nominal yang valid"; return }
        if (actionLoading) return
        viewModelScope.launch {
            actionLoading = true
            error         = null
            runCatching {
                val resp = api.counterOffer(tripId, CounterOfferRequest(counterFare))
                if (resp.isSuccessful) {
                    showCounter = false
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
