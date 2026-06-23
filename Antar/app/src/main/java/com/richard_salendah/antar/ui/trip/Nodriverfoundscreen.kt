package com.richard_salendah.antar.ui.trip

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.richard_salendah.antar.Antar
import com.richard_salendah.antar.data.model.RejectedDriverResponse
import com.richard_salendah.antar.data.model.ReselectDriverRequest
import kotlinx.coroutines.launch

private val PrimaryBlue = Color(0xFF1B6CA8)
private val Red         = Color(0xFFE53935)

// ── ViewModel ─────────────────────────────────────────────────────────────────

class NoDriverFoundViewModel(app: Application) : AndroidViewModel(app) {

    private val api = (app as Antar).apiService

    var drivers       by mutableStateOf<List<RejectedDriverResponse>>(emptyList())
    var loading       by mutableStateOf(false)
    var error         by mutableStateOf<String?>(null)
    // Holds the driverId currently being reselected so the card can show a spinner.
    var actionLoading by mutableStateOf<String?>(null)
    var cancelLoading by mutableStateOf(false)
    var cancelError   by mutableStateOf<String?>(null)

    fun loadDrivers(tripId: String) {
        viewModelScope.launch {
            loading = true
            error   = null
            runCatching {
                val resp = api.getRejectedDrivers(tripId)
                if (resp.isSuccessful) {
                    drivers = resp.body()?.data ?: emptyList()
                } else {
                    error = "Gagal memuat daftar driver"
                }
            }.onFailure { error = "Tidak dapat terhubung ke server" }
            loading = false
        }
    }

    /**
     * Re-assigns a previously rejected driver. The server validates their availability,
     * removes the exclusion row, and auto-approves — no second review step.
     * On success, navigates back to CandidateReview (fresh instance).
     * On failure, refreshes the list so availability badges update.
     */
    fun reselectDriver(tripId: String, driverId: String, onSuccess: () -> Unit) {
        if (actionLoading != null) return
        viewModelScope.launch {
            actionLoading = driverId
            error         = null
            runCatching {
                val resp = api.reselectDriver(tripId, ReselectDriverRequest(driverId))
                if (resp.isSuccessful) {
                    onSuccess()
                } else {
                    error = runCatching {
                        org.json.JSONObject(resp.errorBody()?.string() ?: "")
                            .optString("error").takeIf { it.isNotEmpty() }
                    }.getOrNull() ?: "Driver tidak tersedia, coba yang lain"
                    // Refresh to reflect updated availability
                    loadDrivers(tripId)
                }
            }.onFailure { error = "Tidak dapat terhubung ke server" }
            actionLoading = null
        }
    }

    fun cancelTrip(tripId: String, onCancelled: () -> Unit) {
        if (cancelLoading) return
        viewModelScope.launch {
            cancelLoading = true
            cancelError   = null
            runCatching {
                val resp = api.cancelTrip(tripId)
                if (resp.isSuccessful) onCancelled()
                else cancelError = "Gagal membatalkan pesanan"
            }.onFailure { cancelError = "Tidak dapat terhubung ke server" }
            cancelLoading = false
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun NoDriverFoundScreen(
    tripId: String,
    onDriverReselected: () -> Unit,
    onTripCancelled: () -> Unit,
    viewModel: NoDriverFoundViewModel = viewModel(),
) {
    var showCancelDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.loadDrivers(tripId) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F6F9)),
    ) {
        // ── Blue header ───────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(PrimaryBlue)
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier            = Modifier.fillMaxWidth(),
            ) {
                Surface(
                    shape    = CircleShape,
                    color    = Color.White.copy(alpha = 0.18f),
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.DirectionsCar, null,
                            tint     = Color.White,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Tidak Ada Driver Tersedia",
                    style     = MaterialTheme.typography.titleLarge.copy(
                        color = Color.White, fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Pilih driver dari daftar sebelumnya atau batalkan pesanan",
                    style     = MaterialTheme.typography.bodySmall.copy(
                        color = Color.White.copy(alpha = 0.82f)),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // ── Driver list ───────────────────────────────────────────────────────
        when {
            viewModel.loading -> {
                Box(
                    modifier         = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = PrimaryBlue) }
            }
            viewModel.drivers.isEmpty() && !viewModel.loading -> {
                Box(
                    modifier         = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.DirectionsCar, null,
                            tint     = Color(0xFFCCCCCC),
                            modifier = Modifier.size(52.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Tidak ada riwayat driver",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = Color(0xFF999999)),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Batalkan pesanan dan coba lagi nanti",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color(0xFFBBBBBB)),
                        )
                    }
                }
            }
            else -> {
                if (viewModel.error != null) {
                    Text(
                        viewModel.error!!, color = MaterialTheme.colorScheme.error,
                        style    = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                LazyColumn(
                    modifier        = Modifier.weight(1f),
                    contentPadding  = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(viewModel.drivers, key = { it.driverId }) { driver ->
                        RejectedDriverCard(
                            driver     = driver,
                            isLoading  = viewModel.actionLoading == driver.driverId,
                            anyLoading = viewModel.actionLoading != null,
                            onReselect = {
                                viewModel.reselectDriver(tripId, driver.driverId, onDriverReselected)
                            },
                        )
                    }
                }
            }
        }

        // ── Bottom cancel bar ─────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .navigationBarsPadding()
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
                enabled  = !viewModel.cancelLoading && viewModel.actionLoading == null,
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

// ── Rejected driver card ──────────────────────────────────────────────────────

@Composable
private fun RejectedDriverCard(
    driver: RejectedDriverResponse,
    isLoading: Boolean,
    anyLoading: Boolean,
    onReselect: () -> Unit,
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(14.dp),
        colors    = CardDefaults.cardColors(
            containerColor = if (driver.isAvailable) Color.White else Color(0xFFF7F7F7),
        ),
        elevation = CardDefaults.cardElevation(if (driver.isAvailable) 1.dp else 0.dp),
    ) {
        Row(
            modifier              = Modifier.padding(14.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Avatar
            if (!driver.avatarUrl.isNullOrEmpty()) {
                AsyncImage(
                    model              = driver.avatarUrl,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(52.dp).clip(CircleShape),
                )
            } else {
                Surface(
                    shape    = CircleShape,
                    color    = if (driver.isAvailable) Color(0xFFE8F4FD) else Color(0xFFEEEEEE),
                    modifier = Modifier.size(52.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            Icons.Default.Person, null,
                            tint     = if (driver.isAvailable) PrimaryBlue else Color(0xFFBBBBBB),
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
            }
            // Driver info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    driver.fullName,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color      = if (driver.isAvailable) Color(0xFF1A1A1A) else Color(0xFFAAAAAA)),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    driver.vehicleType,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = if (driver.isAvailable) Color(0xFF777777) else Color(0xFFBBBBBB)),
                )
                if (driver.avgRating != null) {
                    Spacer(Modifier.height(3.dp))
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            Icons.Default.Star, null,
                            tint     = if (driver.isAvailable) Color(0xFFFFA000) else Color(0xFFDDDDDD),
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            "%.1f".format(driver.avgRating),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (driver.isAvailable) Color(0xFF666666) else Color(0xFFBBBBBB)),
                        )
                    }
                }
                if (!driver.isAvailable) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "Tidak tersedia",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = Red.copy(alpha = 0.55f)),
                    )
                }
            }
            // Reselect button — only for available drivers
            if (driver.isAvailable) {
                Button(
                    onClick        = onReselect,
                    enabled        = !anyLoading,
                    modifier       = Modifier.height(36.dp),
                    shape          = RoundedCornerShape(8.dp),
                    colors         = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color       = Color.White,
                            modifier    = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            "Pilih",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        }
    }
}