package com.richard_salendah.antar.ui.trip

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
                Text("Penawaran Driver",
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = Color.White, fontWeight = FontWeight.Bold))
                Text("Tinjau penawaran harga dari driver Anda",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = Color.White.copy(alpha = 0.8f)))
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (trip == null) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PrimaryBlue)
                }
            } else {
                // ── Fare display ──────────────────────────────────────────────
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(2.dp)) {
                    Column(modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Harga Ditawarkan",
                            style = MaterialTheme.typography.labelLarge.copy(color = Color(0xFF999999)))
                        Spacer(Modifier.height(8.dp))
                        Text(formatRupiah(trip.offeredFare ?: 0.0),
                            style = MaterialTheme.typography.displaySmall.copy(
                                color = PrimaryBlue, fontWeight = FontWeight.ExtraBold))
                        Spacer(Modifier.height(4.dp))
                        val roundLabel = when (trip.lastOfferBy) {
                            "driver" -> "Penawaran dari Driver"
                            "rider"  -> "Menunggu respons Driver…"
                            else     -> ""
                        }
                        if (roundLabel.isNotEmpty()) {
                            Text(roundLabel, style = MaterialTheme.typography.bodySmall.copy(
                                color = if (trip.lastOfferBy == "driver") Green else Color(0xFFF57F17)))
                        }
                        if (trip.offerRound > 1) {
                            Spacer(Modifier.height(4.dp))
                            Text("Putaran ke-${trip.offerRound}",
                                style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFAAAAAA)))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Trip detail card ──────────────────────────────────────────
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(2.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        DetailRow(Icons.Default.LocationOn, PrimaryBlue, "Penjemputan", trip.pickupAddress)
                        if (trip.tripType == "transport" && !trip.dropoffAddress.isNullOrBlank()) {
                            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFFF0F0F0))
                            DetailRow(Icons.Default.LocationOn, Red, "Tujuan", trip.dropoffAddress!!)
                        } else if (trip.tripType == "errand" && !trip.note.isNullOrBlank()) {
                            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFFF0F0F0))
                            DetailRow(Icons.Default.Payments, Color(0xFF777777), "Keterangan", trip.note!!)
                        }
                    }
                }

                // ── Counter input ─────────────────────────────────────────────
                if (viewModel.showCounter) {
                    Spacer(Modifier.height(12.dp))
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(2.dp)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Tawar Balik", style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold, color = PrimaryBlue))
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value         = viewModel.counterInput,
                                onValueChange = {
                                    viewModel.counterInput = it.filter { c -> c.isDigit() }
                                    viewModel.error = null
                                },
                                label           = { Text("Nominal tawar (Rp)") },
                                singleLine      = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier        = Modifier.fillMaxWidth(),
                                shape           = RoundedCornerShape(10.dp),
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick  = { viewModel.showCounter = false; viewModel.counterInput = "" },
                                    modifier = Modifier.weight(1f),
                                    shape    = RoundedCornerShape(10.dp),
                                ) { Text("Batal") }
                                Button(
                                    onClick = {
                                        haptic.perform(HapticType.Tick) // ← haptic on counter send
                                        viewModel.submitCounter(tripId)
                                    },
                                    enabled  = !viewModel.actionLoading,
                                    modifier = Modifier.weight(1f),
                                    shape    = RoundedCornerShape(10.dp),
                                    colors   = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                ) {
                                    if (viewModel.actionLoading)
                                        CircularProgressIndicator(color = Color.White,
                                            modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else Text("Kirim Tawar")
                                }
                            }
                        }
                    }
                }

                if (viewModel.error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(viewModel.error!!, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }

                Spacer(Modifier.height(8.dp))

                if (trip.lastOfferBy == "rider") {
                    Text("Menunggu respons driver…",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF999999)),
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
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
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isRiderTurn && !viewModel.showCounter) {
                    Button(
                        onClick = {
                            haptic.perform(HapticType.Confirm) // ← haptic on accept
                            viewModel.accept(tripId, onOfferAccepted)
                        },
                        enabled  = !viewModel.actionLoading,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape    = RoundedCornerShape(12.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = Green),
                    ) {
                        if (viewModel.actionLoading)
                            CircularProgressIndicator(color = Color.White,
                                modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Terima Penawaran", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick  = { viewModel.showCounter = true },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape    = RoundedCornerShape(12.dp),
                            colors   = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryBlue),
                        ) {
                            Icon(Icons.Default.SwapVert, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Tawar", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = {
                                haptic.perform(HapticType.Tick) // ← haptic on reject
                                viewModel.reject(tripId, onTripReset)
                            },
                            enabled  = !viewModel.actionLoading,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape    = RoundedCornerShape(12.dp),
                            colors   = ButtonDefaults.outlinedButtonColors(contentColor = Red),
                        ) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Tolak", fontWeight = FontWeight.SemiBold)
                        }
                    }
                } else if (!isRiderTurn) {
                    TextButton(
                        onClick  = { viewModel.reject(tripId, onTripReset) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Batalkan Penawaran", color = Red, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    label: String,
    value: String,
) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFAAAAAA)))
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium.copy(
                color = Color(0xFF1A1A1A), fontWeight = FontWeight.Medium))
        }
    }
}

private fun formatRupiah(amount: Double) =
    "Rp " + NumberFormat.getNumberInstance(Locale("id", "ID")).format(amount.toLong())