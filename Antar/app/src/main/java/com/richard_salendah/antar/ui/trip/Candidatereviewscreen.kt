package com.richard_salendah.antar.ui.trip

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.richard_salendah.antar.Antar
import com.richard_salendah.antar.data.model.ApproveCandidateRequest
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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// ── Constants & helpers ───────────────────────────────────────────────────────

private const val CANDIDATE_TIMEOUT_SECONDS = 180L // 3 minutes, mirrors cron timeout

private val PrimaryBlue = Color(0xFF1B6CA8)
private val Green       = Color(0xFF2E7D32)
private val Amber       = Color(0xFFF57F17)
private val AmberLight  = Color(0xFFFFF8E1)
private val Red         = Color(0xFFE53935)

/**
 * Parses a PostgreSQL timestamptz text value to epoch-millis (UTC).
 * Handles both "2024-01-15T10:30:00Z" and "2024-01-15 10:30:00.123456+00" formats.
 * Returns null on any parse failure so callers can handle gracefully.
 */
internal fun parseUtcMillis(ts: String?): Long? {
    ts ?: return null
    return try {
        val s = ts.trim()
            .replace(' ', 'T')                    // postgres space → ISO T
            .replace(Regex("\\.[0-9]+"), "")      // strip fractional seconds
            .replace("+00:00", "").replace("+00", "")
            .removeSuffix("Z")
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .parse(s)?.time
    } catch (_: Exception) { null }
}

private fun formatCountdown(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "${m}:${s.toString().padStart(2, '0')}"
}

private fun parseApiError(body: String?): String? = runCatching {
    org.json.JSONObject(body ?: "").optString("error").takeIf { it.isNotEmpty() }
}.getOrNull()

// ── ViewModel ─────────────────────────────────────────────────────────────────

class CandidateReviewViewModel(app: Application) : AndroidViewModel(app) {

    private val api      = (app as Antar).apiService
    private val supabase = (app as Antar).supabase

    var trip                  by mutableStateOf<TripResponse?>(null)
    var loading               by mutableStateOf(false)
    var error                 by mutableStateOf<String?>(null)
    var actionLoading         by mutableStateOf(false)
    var cancelLoading         by mutableStateOf(false)
    var cancelError           by mutableStateOf<String?>(null)
    var showNonResponseDialog by mutableStateOf(false)

    private var channel: RealtimeChannel? = null
    private var pollJob: Job?             = null
    private var started = false

    fun start(
        tripId: String,
        onOfferReceived: () -> Unit,
        onNoDriverFound: () -> Unit,
        onTripCancelled: () -> Unit,
    ) {
        if (started) return
        started = true
        loadTrip(tripId, onOfferReceived, onTripCancelled)
        subscribeRealtime(tripId, onOfferReceived, onTripCancelled)
        startPolling(tripId, onOfferReceived, onTripCancelled)
    }

    private fun loadTrip(tripId: String, onOfferReceived: () -> Unit, onTripCancelled: () -> Unit) {
        viewModelScope.launch {
            loading = true
            error   = null
            runCatching {
                val resp = api.getTrip(tripId)
                if (resp.isSuccessful) {
                    val t = resp.body()?.data ?: return@runCatching
                    trip = t
                    handleTrip(t, onOfferReceived, onTripCancelled)
                } else error = "Gagal memuat detail pesanan"
            }.onFailure { error = "Tidak dapat terhubung ke server" }
            loading = false
        }
    }

    private fun subscribeRealtime(tripId: String, onOfferReceived: () -> Unit, onTripCancelled: () -> Unit) {
        viewModelScope.launch {
            runCatching {
                val ch = supabase.channel("candidate_review_$tripId-${System.currentTimeMillis()}")
                channel = ch
                ch.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "trips"
                    filter("id", FilterOperator.EQ, tripId)
                }.onEach { _ ->
                    // Re-fetch to get full candidate fields; Realtime payload alone is insufficient
                    viewModelScope.launch {
                        runCatching {
                            val resp = api.getTrip(tripId)
                            if (resp.isSuccessful) {
                                val t = resp.body()?.data ?: return@runCatching
                                trip = t
                                handleTrip(t, onOfferReceived, onTripCancelled)
                            }
                        }
                    }
                }.launchIn(viewModelScope)
                ch.subscribe()
            }
        }
    }

    private fun startPolling(tripId: String, onOfferReceived: () -> Unit, onTripCancelled: () -> Unit) {
        pollJob = viewModelScope.launch {
            while (true) {
                delay(5_000L)
                runCatching {
                    val resp = api.getTrip(tripId)
                    if (resp.isSuccessful) {
                        val t = resp.body()?.data ?: return@runCatching
                        trip = t
                        handleTrip(t, onOfferReceived, onTripCancelled)
                    }
                }
            }
        }
    }

    private fun handleTrip(t: TripResponse, onOfferReceived: () -> Unit, onTripCancelled: () -> Unit) {
        when (t.status) {
            "offered"   -> { stopWatching(); onOfferReceived() }
            "cancelled" -> { stopWatching(); onTripCancelled() }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    /**
     * Approve the suggested candidate driver. The server fires FCM to the driver.
     * Refreshes the trip immediately to get the candidateApprovedAt timestamp,
     * which starts the client-side 3-minute countdown.
     */
    fun approve(tripId: String, driverId: String) {
        if (actionLoading) return
        viewModelScope.launch {
            actionLoading = true
            error         = null
            runCatching {
                val resp = api.approveCandidate(tripId, ApproveCandidateRequest(driverId))
                if (resp.isSuccessful) {
                    val refresh = api.getTrip(tripId)
                    if (refresh.isSuccessful) trip = refresh.body()?.data
                } else {
                    error = parseApiError(resp.errorBody()?.string()) ?: "Gagal menyetujui driver"
                }
            }.onFailure { error = "Tidak dapat terhubung ke server" }
            actionLoading = false
        }
    }

    /**
     * Reject the current candidate. Server finds the next nearest eligible driver
     * and returns their ID in the response, or null if no eligible drivers remain.
     * Navigates to NoDriverFound only when the response candidateDriverId is null.
     */
    fun rejectCandidate(tripId: String, onNoDriverFound: () -> Unit) {
        if (actionLoading) return
        viewModelScope.launch {
            actionLoading         = true
            error                 = null
            showNonResponseDialog = false
            runCatching {
                val resp = api.rejectCandidate(tripId)
                if (resp.isSuccessful) {
                    val newCandidateId = resp.body()?.data?.candidateDriverId
                    if (newCandidateId == null) {
                        stopWatching()
                        onNoDriverFound()
                    } else {
                        // New candidate assigned; refresh to display updated card
                        val refresh = api.getTrip(tripId)
                        if (refresh.isSuccessful) trip = refresh.body()?.data
                    }
                } else {
                    error = parseApiError(resp.errorBody()?.string()) ?: "Gagal menemukan driver lain"
                }
            }.onFailure { error = "Tidak dapat terhubung ke server" }
            actionLoading = false
        }
    }

    fun cancelTrip(tripId: String, onCancelled: () -> Unit) {
        if (cancelLoading) return
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

    override fun onCleared() { super.onCleared(); stopWatching() }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun CandidateReviewScreen(
    tripId: String,
    onOfferReceived: () -> Unit,
    onNoDriverFound: () -> Unit,
    onTripCancelled: () -> Unit,
    viewModel: CandidateReviewViewModel = viewModel(),
) {
    var showCancelDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.start(tripId, onOfferReceived, onNoDriverFound, onTripCancelled)
    }

    val trip = viewModel.trip
    val candidateApprovedAt = trip?.candidateApprovedAt
    val candidateApproved   = trip?.candidateApproved ?: false

    // ── Countdown when candidate is approved ──────────────────────────────────
    // Ticks every 500ms; triggers showNonResponseDialog when it reaches zero.
    var countdownSeconds by remember { mutableLongStateOf(CANDIDATE_TIMEOUT_SECONDS) }
    LaunchedEffect(candidateApprovedAt, candidateApproved) {
        if (!candidateApproved || candidateApprovedAt == null) {
            countdownSeconds = CANDIDATE_TIMEOUT_SECONDS
            return@LaunchedEffect
        }
        val approvedMs = parseUtcMillis(candidateApprovedAt) ?: run {
            viewModel.showNonResponseDialog = true
            return@LaunchedEffect
        }
        while (true) {
            val remaining = ((approvedMs + CANDIDATE_TIMEOUT_SECONDS * 1000L) - System.currentTimeMillis()) / 1000L
            countdownSeconds = remaining.coerceAtLeast(0L)
            if (remaining <= 0L) {
                viewModel.showNonResponseDialog = true
                break
            }
            delay(500L)
        }
    }

    // ── Auto-confirm countdown inside the non-response dialog ─────────────────
    // Counts down from 30s; at zero it automatically calls rejectCandidate.
    var autoConfirmSec by remember { mutableStateOf(30) }
    LaunchedEffect(viewModel.showNonResponseDialog) {
        if (!viewModel.showNonResponseDialog) { autoConfirmSec = 30; return@LaunchedEffect }
        var i = 30
        while (i >= 0) {
            autoConfirmSec = i
            if (i == 0) { viewModel.rejectCandidate(tripId, onNoDriverFound); break }
            delay(1_000L)
            i--
        }
    }

    // ── Cancel confirm dialog ─────────────────────────────────────────────────
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Batalkan Pesanan?") },
            text  = { Text("Pencarian driver akan dihentikan dan Anda harus memesan ulang.") },
            confirmButton = {
                Button(
                    onClick = { showCancelDialog = false; viewModel.cancelTrip(tripId, onTripCancelled) },
                    colors  = ButtonDefaults.buttonColors(containerColor = Red),
                ) { Text("Ya, Batalkan") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) { Text("Tidak") }
            },
        )
    }

    // ── Non-response dialog ───────────────────────────────────────────────────
    if (viewModel.showNonResponseDialog) {
        AlertDialog(
            onDismissRequest = { /* intentionally non-dismissible; user must choose */ },
            title = { Text("Driver Tidak Merespons") },
            text  = {
                Text(
                    "Driver tidak merespons tepat waktu. " +
                            "Kami akan mencari driver lain, atau batalkan pesanan."
                )
            },
            confirmButton = {
                Button(
                    onClick  = { viewModel.rejectCandidate(tripId, onNoDriverFound) },
                    enabled  = !viewModel.actionLoading,
                    colors   = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                ) {
                    if (viewModel.actionLoading) {
                        CircularProgressIndicator(
                            color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Cari Driver Lain ($autoConfirmSec)")
                    }
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick  = {
                        viewModel.showNonResponseDialog = false
                        showCancelDialog                = true
                    },
                    enabled  = !viewModel.actionLoading,
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                ) { Text("Batalkan Pesanan") }
            },
        )
    }

    // ── Main content ──────────────────────────────────────────────────────────
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F6F9))
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            when {
                viewModel.loading && trip == null -> {
                    CircularProgressIndicator(color = PrimaryBlue, modifier = Modifier.size(52.dp))
                }
                viewModel.error != null && trip == null -> {
                    Text(
                        viewModel.error!!, color = MaterialTheme.colorScheme.error,
                        style    = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                trip != null -> {
                    val hasCandidate = trip.candidateDriverId != null
                    val isApproved   = trip.candidateApproved
                    // candidateDriverId == null + notificationAttempts > 0 means the cron
                    // exhausted all nearby drivers; there's nobody left to assign.
                    val isExhausted  = !hasCandidate && trip.notificationAttempts > 0

                    when {
                        isExhausted         -> ExhaustedState(onNoDriverFound)
                        !hasCandidate       -> SearchingState()
                        !isApproved         -> ReviewState(
                            trip          = trip,
                            actionLoading = viewModel.actionLoading,
                            onApprove     = { viewModel.approve(tripId, trip.candidateDriverId!!) },
                            onReject      = { viewModel.rejectCandidate(tripId, onNoDriverFound) },
                        )
                        else                -> WaitingState(
                            trip             = trip,
                            countdownSeconds = countdownSeconds,
                        )
                    }

                    if (viewModel.error != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            viewModel.error!!, color = MaterialTheme.colorScheme.error,
                            style     = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }

        // ── Cancel button (always visible except while non-response dialog is open) ──
        if (!viewModel.showNonResponseDialog) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                if (viewModel.cancelError != null) {
                    Text(
                        viewModel.cancelError!!, color = MaterialTheme.colorScheme.error,
                        style    = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                OutlinedButton(
                    onClick  = { showCancelDialog = true },
                    enabled  = !viewModel.cancelLoading && !viewModel.actionLoading,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                ) {
                    if (viewModel.cancelLoading) {
                        CircularProgressIndicator(color = Red, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Batalkan Pesanan", fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// ── Content states ────────────────────────────────────────────────────────────

/** No candidate assigned yet; cron trigger is finding a nearby driver. */
@Composable
private fun SearchingState() {
    CircularProgressIndicator(
        color       = PrimaryBlue,
        modifier    = Modifier.size(64.dp),
        strokeWidth = 5.dp,
    )
    Spacer(Modifier.height(28.dp))
    Text(
        "Mencari Driver Terdekat",
        style     = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A)),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Sistem sedang mencarikan driver terbaik di area Anda. Mohon tunggu sebentar.",
        style     = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF777777)),
        textAlign = TextAlign.Center,
        modifier  = Modifier.padding(horizontal = 8.dp),
    )
}

/** Cron exhausted all nearby drivers; notification_attempts reached the limit. */
@Composable
private fun ExhaustedState(onViewRejected: () -> Unit) {
    Surface(shape = CircleShape, color = Color(0xFFFFEBEE), modifier = Modifier.size(80.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                Icons.Default.DirectionsCar, null,
                tint     = Red,
                modifier = Modifier.size(40.dp),
            )
        }
    }
    Spacer(Modifier.height(20.dp))
    Text(
        "Tidak Ada Driver Tersedia",
        style     = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A)),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Semua driver di area Anda tidak merespons. " +
                "Anda dapat memilih dari driver yang sudah Anda tolak sebelumnya.",
        style     = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF777777)),
        textAlign = TextAlign.Center,
        modifier  = Modifier.padding(horizontal = 8.dp),
    )
    Spacer(Modifier.height(24.dp))
    Button(
        onClick  = onViewRejected,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape    = RoundedCornerShape(12.dp),
        colors   = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
    ) {
        Text("Lihat Driver Sebelumnya", fontWeight = FontWeight.SemiBold)
    }
}

/** Candidate is assigned; rider reviews and decides to approve or find another. */
@Composable
private fun ReviewState(
    trip: TripResponse,
    actionLoading: Boolean,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Text(
        "Driver Ditemukan!",
        style     = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A)),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Tinjau driver berikut sebelum melanjutkan",
        style     = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF777777)),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    CandidateDriverCard(trip)
    Spacer(Modifier.height(20.dp))
    Button(
        onClick  = onApprove,
        enabled  = !actionLoading,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape    = RoundedCornerShape(12.dp),
        colors   = ButtonDefaults.buttonColors(containerColor = Green),
    ) {
        if (actionLoading) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Setujui Driver Ini", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    Spacer(Modifier.height(10.dp))
    OutlinedButton(
        onClick  = onReject,
        enabled  = !actionLoading,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape    = RoundedCornerShape(12.dp),
        colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF666666)),
    ) {
        Text("Cari Driver Lain", fontWeight = FontWeight.Medium)
    }
}

/** Candidate approved; rider waits for driver to respond (make an offer). */
@Composable
private fun WaitingState(trip: TripResponse, countdownSeconds: Long) {
    val isLow = countdownSeconds in 1..30
    Text(
        "Menunggu Konfirmasi Driver",
        style     = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A)),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Driver sedang mempertimbangkan permintaan Anda",
        style     = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFF777777)),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    CandidateDriverCard(trip)
    Spacer(Modifier.height(16.dp))
    // Countdown badge
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isLow) Color(0xFFFFEBEE) else AmberLight,
    ) {
        Row(
            modifier              = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(
                color       = if (isLow) Red else Amber,
                modifier    = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Text(
                if (countdownSeconds > 0) "${formatCountdown(countdownSeconds)} tersisa"
                else "Menunggu respons driver…",
                style = MaterialTheme.typography.labelLarge.copy(
                    color      = if (isLow) Red else Amber,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

// ── Shared driver card ────────────────────────────────────────────────────────

@Composable
private fun CandidateDriverCard(trip: TripResponse) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Row(
            modifier              = Modifier.padding(16.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Avatar
            if (!trip.candidateDriverAvatarUrl.isNullOrEmpty()) {
                AsyncImage(
                    model              = trip.candidateDriverAvatarUrl,
                    contentDescription = "Foto driver",
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier
                        .size(64.dp)
                        .clip(CircleShape),
                )
            } else {
                Surface(
                    shape    = CircleShape,
                    color    = Color(0xFFE8F4FD),
                    modifier = Modifier.size(64.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.Person, null,
                            tint     = PrimaryBlue,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
            // Name, vehicle, rating
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    trip.candidateDriverName ?: "Driver",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A)),
                )
                Spacer(Modifier.height(4.dp))
                trip.candidateVehicleType?.let { vt ->
                    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFE8F4FD)) {
                        Text(
                            vt,
                            style    = MaterialTheme.typography.labelSmall.copy(
                                color = PrimaryBlue, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                // Rating row
                val rating = trip.candidateDriverRating
                if (rating != null) {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Default.Star, null,
                            tint     = Color(0xFFFFA000),
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            "%.1f".format(rating),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color      = Color(0xFF666666),
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    }
                } else {
                    Text(
                        "Driver Baru",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFAAAAAA)),
                    )
                }
            }
        }
    }
}