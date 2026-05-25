package com.richard_salendah.antar.ui.booking

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ElectricRickshaw
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.richard_salendah.antar.ui.common.HapticType
import com.richard_salendah.antar.ui.common.rememberHaptic

private val PrimaryBlue = Color(0xFF1B6CA8)

@Composable
fun BookingConfirmScreen(
    onTripRequested: (tripId: String) -> Unit,
    onBack: () -> Unit,
) {
    val activity = LocalContext.current as ComponentActivity
    val vm: BookingViewModel = viewModel(activity)
    val haptic = rememberHaptic()

    val isTransport = vm.tripType == "transport"

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = PrimaryBlue)
            }
            Text("Konfirmasi Pesanan",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A)))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // ── Order summary card ────────────────────────────────────────────
            Card(
                modifier  = Modifier.fillMaxWidth(),
                shape     = RoundedCornerShape(16.dp),
                colors    = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(2.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFE8F4FD)) {
                            Text(
                                if (isTransport) "Antar" else "Errand",
                                style    = MaterialTheme.typography.labelMedium.copy(
                                    color = PrimaryBlue, fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                        vm.selectedType?.let { vt ->
                            Icon(vehicleIcon(vt.code), null, tint = PrimaryBlue, modifier = Modifier.size(18.dp))
                            Text(vt.name, style = MaterialTheme.typography.bodyMedium.copy(
                                color = Color(0xFF444444), fontWeight = FontWeight.SemiBold))
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Color(0xFFF0F0F0))

                    SummaryRow(Icons.Default.LocationOn, PrimaryBlue, "Penjemputan",
                        vm.pickupAddress.ifBlank { "Lokasi Anda" })

                    if (isTransport) {
                        Spacer(Modifier.height(12.dp))
                        SummaryRow(Icons.Default.LocationOn, Color(0xFFE53935), "Tujuan", vm.dropoffAddress)
                    } else {
                        Spacer(Modifier.height(12.dp))
                        SummaryRow(Icons.AutoMirrored.Filled.Notes, Color(0xFF777777), "Keterangan", vm.note)
                    }

                    HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Color(0xFFF0F0F0))
                    SummaryRow(Icons.Default.Payments, Color(0xFF4CAF50), "Pembayaran", "Tunai")
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Price info card ───────────────────────────────────────────────
            Card(
                modifier  = Modifier.fillMaxWidth(),
                shape     = RoundedCornerShape(12.dp),
                colors    = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                elevation = CardDefaults.cardElevation(0.dp),
            ) {
                Row(modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top) {
                    Icon(Icons.Default.Info, null, tint = Color(0xFFF57F17), modifier = Modifier.size(18.dp))
                    Text(
                        "Harga ditentukan oleh driver saat penawaran. " +
                                "Anda dapat menerima, menolak, atau menawar balik.",
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF5D4037)),
                    )
                }
            }

            if (vm.bookingError != null) {
                Spacer(Modifier.height(12.dp))
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                    Text(vm.bookingError!!, color = MaterialTheme.colorScheme.error,
                        style    = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        // ── Confirm button ────────────────────────────────────────────────────
        Surface(shadowElevation = 8.dp) {
            Button(
                onClick = {
                    vm.requestRide(
                        onSuccess = { tripId ->
                            haptic.perform(HapticType.Confirm) // ← haptic on booking
                            onTripRequested(tripId)
                        },
                        onError = { err ->
                            haptic.perform(HapticType.Error)   // ← haptic on error
                            vm.bookingError = err
                        },
                    )
                },
                enabled  = !vm.bookingLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .height(56.dp),
                shape  = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
            ) {
                if (vm.bookingLoading) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                } else {
                    Text("Pesan Sekarang", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(icon: ImageVector, iconTint: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF999999)))
            Spacer(Modifier.height(2.dp))
            Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium.copy(
                color = Color(0xFF1A1A1A), fontWeight = FontWeight.Medium))
        }
    }
}

private fun vehicleIcon(code: String): ImageVector = when {
    code.contains("motor",   ignoreCase = true) ||
            code.contains("bike",    ignoreCase = true)   -> Icons.Default.TwoWheeler
    code.contains("bentor",  ignoreCase = true) ||
            code.contains("rickshaw",ignoreCase = true)   -> Icons.Default.ElectricRickshaw
    else                                          -> Icons.Default.DirectionsCar
}