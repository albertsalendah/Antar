package com.richard_salendah.driverantar.ui.trip

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Intent
import android.net.Uri
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveTripScreen(
    viewModel: ActiveTripViewModel,
    onTripCompleted: (tripId: String) -> Unit,
    onTripCancelled: () -> Unit
) {
    val context = LocalContext.current
    val uiState = viewModel.uiState
    val trip    = viewModel.trip
    val haptic = LocalHapticFeedback.current

    // ── Navigation side-effects ───────────────────────────────────────────────
    LaunchedEffect(uiState) {
        when (uiState) {
            is ActiveTripUiState.Completed -> onTripCompleted(viewModel.tripId)
            is ActiveTripUiState.Cancelled -> onTripCancelled()
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (trip?.status) {
                                "agreed"      -> "Menuju Penumpang"
                                "in_progress" -> "Perjalanan Berlangsung"
                                else          -> "Trip Aktif"
                            }
                        )
                        trip?.status?.let { status ->
                            Text(
                                statusLabel(status),
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor(status)
                            )
                        }
                    }
                }
                // No back button — driver must complete or cancel explicitly
            )
        }
    ) { padding ->

        when {
            // ── Loading ───────────────────────────────────────────────────────
            uiState is ActiveTripUiState.Loading || trip == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            // ── Error ─────────────────────────────────────────────────────────
            uiState is ActiveTripUiState.Error -> {
                Box(
                    Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(uiState.message, style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.clearError() }) { Text("OK") }
                    }
                }
            }

            // ── Active trip UI ────────────────────────────────────────────────
            else -> {
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                ) {
                    // ── Map (top half) ────────────────────────────────────────
                    AndroidView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        factory = { ctx ->
                            MapView(ctx).apply {
                                setTileSource(TileSourceFactory.MAPNIK)
                                setMultiTouchControls(true)
                                controller.setZoom(16.0)

                                val pickupPoint = GeoPoint(trip.pickup_lat, trip.pickup_lng)
                                controller.setCenter(pickupPoint)

                                // Pickup marker
                                val pickupMarker = Marker(this).apply {
                                    position    = pickupPoint
                                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                    title       = "Jemput: ${trip.rider_name}"
                                    snippet     = trip.pickup_address
                                }
                                overlays.add(pickupMarker)

                                // Dropoff marker (transport trips)
                                if (trip.dropoff_lat != null && trip.dropoff_lng != null) {
                                    val dropoffMarker = Marker(this).apply {
                                        position = GeoPoint(trip.dropoff_lat, trip.dropoff_lng)
                                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                        title   = "Tujuan"
                                        snippet = trip.dropoff_address ?: ""
                                    }
                                    overlays.add(dropoffMarker)
                                }

                                invalidate()
                            }
                        },
                        update = { _ -> /* map is static — pickup doesn't move */ }
                    )

                    // ── Trip detail panel (bottom half) ───────────────────────
                    Column(
                        modifier            = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                        // ── Rider info card ───────────────────────────────────
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier              = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment     = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment     = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Filled.Person, null,
                                            tint     = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp))
                                        Column {
                                            Text(trip.rider_name,
                                                style      = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold)
                                            Text(trip.rider_phone,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    // Call rider button
                                    if (trip.rider_phone.isNotBlank()) {
                                        FilledTonalButton(
                                            onClick = {
                                                val intent = Intent(Intent.ACTION_DIAL,
                                                    Uri.parse("tel:${trip.rider_phone}"))
                                                context.startActivity(intent)
                                            },
                                            contentPadding = PaddingValues(
                                                horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Icon(Icons.Filled.Call, null,
                                                modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Hubungi")
                                        }
                                    }
                                }
                            }
                        }

                        // ── Trip info card ────────────────────────────────────
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier            = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Trip type + payment
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment     = Alignment.CenterVertically
                                ) {
                                    TripTypePill(tripType = trip.trip_type)
                                    Text(
                                        trip.payment_method.replaceFirstChar { it.uppercase() },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Errand note
                                if (!trip.note.isNullOrBlank()) {
                                    Card(
                                        colors   = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text("Instruksi",
                                                style      = MaterialTheme.typography.labelSmall,
                                                color      = MaterialTheme.colorScheme.onSecondaryContainer,
                                                fontWeight = FontWeight.Bold)
                                            Spacer(Modifier.height(2.dp))
                                            Text(trip.note,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer)
                                        }
                                    }
                                }

                                // Pickup address
                                AddressLine(
                                    label   = "Jemput",
                                    address = trip.pickup_address,
                                    color   = MaterialTheme.colorScheme.primary
                                )

                                // Dropoff address
                                if (!trip.dropoff_address.isNullOrBlank()) {
                                    AddressLine(
                                        label   = "Tujuan",
                                        address = trip.dropoff_address,
                                        color   = MaterialTheme.colorScheme.error
                                    )
                                } else if (trip.trip_type == "errand") {
                                    Text("Tujuan: belum ditentukan",
                                        style     = MaterialTheme.typography.bodySmall,
                                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontStyle = FontStyle.Italic)
                                }

                                HorizontalDivider()

                                // Fare
                                Row(
                                    modifier              = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Harga Disepakati",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        formatRupiah(trip.fare ?: trip.offered_fare ?: 0.0),
                                        style      = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color      = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        // ── Action buttons ────────────────────────────────────
                        val isLoading = uiState is ActiveTripUiState.ActionLoading

                        when (trip.status) {
                            "agreed" -> {
                                // Two buttons: Start + Cancel
                                Button(
                                    onClick  = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.startTrip()
                                    },
                                    enabled  = !isLoading,
                                    modifier = Modifier.fillMaxWidth().height(52.dp)
                                )  {
                                    if (isLoading) {
                                        CircularProgressIndicator(
                                            Modifier.size(20.dp), strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary)
                                    } else {
                                        Text("Mulai Perjalanan",
                                            style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                                OutlinedButton(
                                    onClick  = { viewModel.requestCancel() },
                                    enabled  = !isLoading,
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    colors   = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Batalkan Trip")
                                }
                            }

                            "in_progress" -> {
                                // Only Complete — can't cancel after starting
                                Button(
                                    onClick  = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.completeTrip()
                                    },
                                    enabled  = !isLoading,
                                    modifier = Modifier.fillMaxWidth().height(52.dp),
                                    colors   = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary
                                    )
                                )  {
                                    if (isLoading) {
                                        CircularProgressIndicator(
                                            Modifier.size(20.dp), strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onTertiary)
                                    } else {
                                        Text("Selesaikan Perjalanan",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onTertiary)
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    // ── Cancel confirmation dialog ────────────────────────────────────────────
    if (uiState is ActiveTripUiState.Confirming) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCancel() },
            title            = { Text("Batalkan Trip?") },
            text             = {
                Text(
                    "Trip akan dikembalikan ke antrean dan penumpang akan " +
                            "mencari driver lain. Yakin ingin membatalkan?"
                )
            },
            confirmButton    = {
                TextButton(onClick = { viewModel.confirmCancel() }) {
                    Text("Ya, Batalkan", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton    = {
                TextButton(onClick = { viewModel.dismissCancel() }) { Text("Tidak") }
            }
        )
    }
}

// ── Small composables ─────────────────────────────────────────────────────────

@Composable
private fun TripTypePill(tripType: String) {
    val isErrand = tripType == "errand"
    val bg = if (isErrand) MaterialTheme.colorScheme.tertiaryContainer
    else          MaterialTheme.colorScheme.primaryContainer
    val fg = if (isErrand) MaterialTheme.colorScheme.onTertiaryContainer
    else          MaterialTheme.colorScheme.onPrimaryContainer
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Text(
            if (isErrand) "Errand" else "Transport",
            color      = fg,
            fontWeight = FontWeight.Bold,
            style      = MaterialTheme.typography.labelSmall,
            modifier   = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun AddressLine(
    label: String,
    address: String,
    color: androidx.compose.ui.graphics.Color
) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.LocationOn, label,
            tint     = color,
            modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Spacer(Modifier.width(4.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(address, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun statusLabel(status: String): String = when (status) {
    "agreed"      -> "Menuju lokasi jemput"
    "in_progress" -> "Perjalanan berjalan"
    else          -> status
}

@Composable
private fun statusColor(status: String) = when (status) {
    "agreed"      -> MaterialTheme.colorScheme.primary
    "in_progress" -> MaterialTheme.colorScheme.tertiary
    else          -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatRupiah(amount: Double): String {
    val fmt = NumberFormat.getNumberInstance(Locale("id", "ID"))
    return "Rp ${fmt.format(amount.roundToInt())}"
}