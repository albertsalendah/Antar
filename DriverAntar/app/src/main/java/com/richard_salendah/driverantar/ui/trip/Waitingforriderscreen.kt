package com.richard_salendah.driverantar.ui.trip

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaitingForRiderScreen(
    viewModel: WaitingForRiderViewModel,
    onTripAgreed: (tripId: String) -> Unit,
    onTripRejected: (message: String) -> Unit,
    onTripCancelled: (message: String) -> Unit,
    onTripWithdrawn: (message: String) -> Unit,
    /**
     * Rider sent a counter-offer.
     * @param tripId            the trip being negotiated
     * @param riderFare         price the rider is proposing
     * @param driverCounterCount how many counters this driver has already used
     * @param defaultFare       admin floor — must be forwarded to CounterDecision
     */
    onRiderCountered: (
        tripId: String,
        riderFare: Double,
        driverCounterCount: Int,
        defaultFare: Double
    ) -> Unit
) {
    val uiState = viewModel.uiState
    var showWithdrawConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        when (uiState) {
            is WaitingUiState.Agreed    -> onTripAgreed(viewModel.tripId)
            is WaitingUiState.Rejected  ->
                onTripRejected("Penumpang menolak penawaran — silakan cari trip lain")
            is WaitingUiState.Cancelled ->
                onTripCancelled("Trip dibatalkan")
            is WaitingUiState.Withdrawn ->
                onTripWithdrawn("Penawaran ditarik — trip dikembalikan ke antrean")
            is WaitingUiState.RiderCountered -> {
                onRiderCountered(
                    viewModel.tripId,
                    uiState.riderFare,
                    uiState.driverCounterCount,  // from Realtime payload — accurate count
                    viewModel.defaultFare         // admin floor, not driver's offered price
                )
            }
            else -> Unit
        }
    }

    val rotation by rememberInfiniteTransition(label = "clock").animateFloat(
        initialValue  = 0f,
        targetValue   = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing)
        ),
        label = "rotation"
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text("Menunggu Respons") }) }
    ) { padding ->
        Column(
            modifier            = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when (uiState) {
                is WaitingUiState.Waiting -> {
                    Icon(
                        Icons.Filled.AccessTime,
                        contentDescription = "Waiting",
                        modifier = Modifier.size(80.dp).rotate(rotation),
                        tint     = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(28.dp))
                    Text(
                        "Penawaran Terkirim!",
                        style      = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Harga yang Anda tawarkan",
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        formatRupiah(viewModel.offeredFare),
                        style      = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.primary,
                        textAlign  = TextAlign.Center
                    )
                    Spacer(Modifier.height(28.dp))
                    Text(
                        "Menunggu penumpang menerima atau menegosiasi harga…",
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.6f))

                    Spacer(Modifier.height(32.dp))
                    OutlinedButton(
                        onClick  = { showWithdrawConfirm = true },
                        enabled  = !viewModel.isWithdrawing,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors   = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        if (viewModel.isWithdrawing) {
                            CircularProgressIndicator(
                                modifier    = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color       = MaterialTheme.colorScheme.error
                            )
                        } else {
                            Text("Tarik Penawaran")
                        }
                    }
                }
                is WaitingUiState.Error -> {
                    Text(
                        uiState.message,
                        style     = MaterialTheme.typography.bodyLarge,
                        color     = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Coba hubungi penumpang secara langsung atau kembali ke daftar trip.",
                        style     = MaterialTheme.typography.bodySmall,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                else -> CircularProgressIndicator()
            }
        }
    }

    // ── Withdraw confirmation dialog ──────────────────────────────────────────
    if (showWithdrawConfirm) {
        AlertDialog(
            onDismissRequest = { showWithdrawConfirm = false },
            title = { Text("Tarik Penawaran?") },
            text  = {
                Text(
                    "Penawaran Anda akan dibatalkan dan trip ini akan ditawarkan ke " +
                            "driver terdekat lainnya. Lanjutkan?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showWithdrawConfirm = false
                    viewModel.withdrawOffer()
                }) {
                    Text("Ya, Tarik", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWithdrawConfirm = false }) { Text("Tidak") }
            }
        )
    }
}

private fun formatRupiah(amount: Double): String {
    val fmt = NumberFormat.getNumberInstance(Locale("id", "ID"))
    return "Rp ${fmt.format(amount.roundToInt())}"
}
