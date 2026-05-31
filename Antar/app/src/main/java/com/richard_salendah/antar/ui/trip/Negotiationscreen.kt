package com.richard_salendah.antar.ui.trip

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.richard_salendah.antar.ui.common.HapticType
import com.richard_salendah.antar.ui.common.rememberHaptic
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

private val PrimaryBlue = Color(0xFF1B6CA8)
private val Green       = Color(0xFF2E7D32)
private val Red         = Color(0xFFE53935)

@Composable
fun NegotiationScreen(
    tripId: String,
    onOfferAccepted: () -> Unit,
    onTripReset: () -> Unit,
    viewModel: NegotiationViewModel = viewModel(),
) {
    val haptic = rememberHaptic()

    LaunchedEffect(Unit) { viewModel.start(tripId, onOfferAccepted, onTripReset) }

    val trip = viewModel.trip

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F6F9))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // ── Header ────────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(PrimaryBlue)
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Column {
                Text(
                    "Penawaran Driver",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = Color.White, fontWeight = FontWeight.Bold),
                )
                Text(
                    "Tinjau penawaran harga dari driver Anda",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color.White.copy(alpha = 0.8f)),
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (trip == null) {
                Box(
                    Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = PrimaryBlue) }
            } else {

                // ── Fare display ──────────────────────────────────────────────
                Card(
                    modifier  = Modifier.fillMaxWidth(),
                    shape     = RoundedCornerShape(16.dp),
                    colors    = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(2.dp),
                ) {
                    Column(
                        modifier            = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "Harga Ditawarkan",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = Color(0xFF999999)),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            formatRupiah(trip.offeredFare ?: 0.0),
                            style = MaterialTheme.typography.displaySmall.copy(
                                color = PrimaryBlue, fontWeight = FontWeight.ExtraBold),
                        )
                        Spacer(Modifier.height(4.dp))
                        val roundLabel = when (trip.lastOfferBy) {
                            "driver" -> "Penawaran dari Driver"
                            "rider"  -> "Menunggu respons Driver…"
                            else     -> ""
                        }
                        if (roundLabel.isNotEmpty()) {
                            Text(
                                roundLabel,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = if (trip.lastOfferBy == "driver") Green
                                    else Color(0xFFF57F17)),
                            )
                        }
                        if (trip.offerRound > 1) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Putaran ke-${trip.offerRound}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Color(0xFFAAAAAA)),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Trip detail card ──────────────────────────────────────────
                Card(
                    modifier  = Modifier.fillMaxWidth(),
                    shape     = RoundedCornerShape(16.dp),
                    colors    = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(2.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        DetailRow(
                            Icons.Default.LocationOn, PrimaryBlue,
                            "Penjemputan", trip.pickupAddress,
                        )
                        if (trip.tripType == "transport" && !trip.dropoffAddress.isNullOrBlank()) {
                            HorizontalDivider(
                                Modifier.padding(vertical = 10.dp),
                                color = Color(0xFFF0F0F0),
                            )
                            DetailRow(
                                Icons.Default.LocationOn, Red,
                                "Tujuan", trip.dropoffAddress!!,
                            )
                        } else if (trip.tripType == "errand" && !trip.note.isNullOrBlank()) {
                            HorizontalDivider(
                                Modifier.padding(vertical = 10.dp),
                                color = Color(0xFFF0F0F0),
                            )
                            DetailRow(
                                Icons.Default.Payments, Color(0xFF777777),
                                "Keterangan", trip.note!!,
                            )
                        }
                    }
                }

                // ── Counter stepper panel ─────────────────────────────────────
                if (viewModel.showCounter) {
                    Spacer(Modifier.height(12.dp))
                    Card(
                        modifier  = Modifier.fillMaxWidth(),
                        shape     = RoundedCornerShape(16.dp),
                        colors    = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(2.dp),
                    ) {
                        Column(
                            modifier            = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "Tawar Balik",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold, color = PrimaryBlue),
                            )

                            Spacer(Modifier.height(16.dp))

                            // ── +/- Stepper row ───────────────────────────────
                            // rawInput keeps the text field value in sync with
                            // both typed edits and button taps
                            var rawInput by remember {
                                mutableStateOf(viewModel.counterFare.roundToInt().toString())
                            }

                            LaunchedEffect(viewModel.counterFare) {
                                val asStr = viewModel.counterFare.roundToInt().toString()
                                if (rawInput != asStr) rawInput = asStr
                            }

                            Row(
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier              = Modifier.fillMaxWidth(),
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        haptic.perform(HapticType.Tick)
                                        viewModel.decrementCounter()
                                    },
                                    modifier       = Modifier.size(52.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    shape          = RoundedCornerShape(12.dp),
                                ) {
                                    Text("−", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                }

                                Spacer(Modifier.width(12.dp))

                                OutlinedTextField(
                                    value         = rawInput,
                                    onValueChange = { input ->
                                        val filtered = input.filter { it.isDigit() }
                                        rawInput = filtered
                                        viewModel.setCounterFare(
                                            filtered.toDoubleOrNull() ?: 0.0
                                        )
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                    ),
                                    textStyle = LocalTextStyle.current.copy(
                                        fontSize   = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign  = TextAlign.Center,
                                        color      = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    singleLine = true,
                                    modifier   = Modifier.width(160.dp),
                                    prefix     = {
                                        Text(
                                            "Rp",
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant),
                                        )
                                    },
                                )

                                Spacer(Modifier.width(12.dp))

                                FilledTonalButton(
                                    onClick = {
                                        haptic.perform(HapticType.Tick)
                                        viewModel.incrementCounter()
                                    },
                                    modifier       = Modifier.size(52.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    shape          = RoundedCornerShape(12.dp),
                                ) {
                                    Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            Spacer(Modifier.height(6.dp))

                            Text(
                                "Ketuk + / − untuk ubah Rp 1.000",
                                style    = MaterialTheme.typography.labelSmall,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )

                            Spacer(Modifier.height(16.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                OutlinedButton(
                                    onClick  = {
                                        viewModel.showCounter = false
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape    = RoundedCornerShape(10.dp),
                                ) { Text("Batal") }

                                Button(
                                    onClick = {
                                        haptic.perform(HapticType.Tick)
                                        viewModel.submitCounter(tripId)
                                    },
                                    enabled  = !viewModel.actionLoading &&
                                            viewModel.counterFare > 0,
                                    modifier = Modifier.weight(1f),
                                    shape    = RoundedCornerShape(10.dp),
                                    colors   = ButtonDefaults.buttonColors(
                                        containerColor = PrimaryBlue),
                                ) {
                                    if (viewModel.actionLoading)
                                        CircularProgressIndicator(
                                            color       = Color.White,
                                            modifier    = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    else Text("Kirim Tawar")
                                }
                            }
                        }
                    }
                }

                // ── Error ─────────────────────────────────────────────────────
                if (viewModel.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        viewModel.error!!,
                        color    = MaterialTheme.colorScheme.error,
                        style    = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(8.dp))

                if (trip.lastOfferBy == "rider") {
                    Text(
                        "Menunggu respons driver…",
                        style    = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFF999999)),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // ── Action buttons ────────────────────────────────────────────────────
        val isRiderTurn = trip?.lastOfferBy == "driver"
        if (trip != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isRiderTurn && !viewModel.showCounter) {
                    Button(
                        onClick = {
                            haptic.perform(HapticType.Confirm)
                            viewModel.accept(tripId, onOfferAccepted)
                        },
                        enabled  = !viewModel.actionLoading,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape    = RoundedCornerShape(12.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = Green),
                    ) {
                        if (viewModel.actionLoading)
                            CircularProgressIndicator(
                                color       = Color.White,
                                modifier    = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        else {
                            Icon(
                                Icons.Default.Check, null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Terima Penawaran",
                                fontSize   = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!viewModel.counterExhausted) {
                            OutlinedButton(
                                onClick  = {
                                    haptic.perform(HapticType.Tick)
                                    viewModel.openCounter()
                                },
                                modifier = Modifier.weight(1f).height(48.dp),
                                shape    = RoundedCornerShape(12.dp),
                                colors   = ButtonDefaults.outlinedButtonColors(
                                    contentColor = PrimaryBlue),
                            ) {
                                Icon(
                                    Icons.Default.SwapVert, null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Tawar", fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Text(
                                "Kesempatan tawar habis — terima atau tolak penawaran",
                                style     = MaterialTheme.typography.labelSmall,
                                color     = MaterialTheme.colorScheme.error,
                                modifier  = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                haptic.perform(HapticType.Tick)
                                viewModel.reject(tripId, onTripReset)
                            },
                            enabled  = !viewModel.actionLoading,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape    = RoundedCornerShape(12.dp),
                            colors   = ButtonDefaults.outlinedButtonColors(
                                contentColor = Red),
                        ) {
                            Icon(
                                Icons.Default.Close, null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Tolak", fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else if (!isRiderTurn) {
                    TextButton(
                        onClick  = { viewModel.reject(tripId, onTripReset) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Batalkan Penawaran",
                            color      = Red,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

// ── Detail row ────────────────────────────────────────────────────────────────

@Composable
private fun DetailRow(
    icon:     androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    label:    String,
    value:    String,
) {
    Row(
        verticalAlignment     = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFAAAAAA)),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color(0xFF1A1A1A), fontWeight = FontWeight.Medium),
            )
        }
    }
}

private fun formatRupiah(amount: Double) =
    "Rp " + NumberFormat.getNumberInstance(Locale("id", "ID")).format(amount.toLong())