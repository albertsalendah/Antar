package com.richard_salendah.driverantar.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.richard_salendah.driverantar.data.model.IncomingTripResponse
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfferPriceScreen(
    viewModel: OfferPriceViewModel,
    trip: IncomingTripResponse,       // passed from IncomingTrips so we can show trip summary
    onBack: () -> Unit,
    onOfferSubmitted: (tripId: String) -> Unit  // navigate to WaitingForRider
) {
    val uiState = viewModel.uiState
    val haptic  = LocalHapticFeedback.current

    // Navigate to WaitingForRider immediately on success
    LaunchedEffect(uiState) {
        if (uiState is OfferUiState.Success) {
            onOfferSubmitted(viewModel.tripId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Set Your Price") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // ── Trip summary card ─────────────────────────────────────────────
            TripSummaryCard(trip = trip)

            // ── Fare stepper card ─────────────────────────────────────────────
            FareStepperCard(viewModel = viewModel)

            // ── Error card ────────────────────────────────────────────────────
            if (uiState is OfferUiState.Error) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier              = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        Text(
                            uiState.message,
                            color    = MaterialTheme.colorScheme.onErrorContainer,
                            style    = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("OK")
                        }
                    }
                }
            }

            // ── Submit button ─────────────────────────────────────────────────
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.submitOffer()
                },
                enabled  = !viewModel.isBelowFloor && uiState !is OfferUiState.Loading,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            )  {
                if (uiState is OfferUiState.Loading) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color       = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(
                        "Kirim Penawaran",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Trip summary ──────────────────────────────────────────────────────────────

@Composable
private fun TripSummaryCard(trip: IncomingTripResponse) {
    val isErrand = trip.trip_type == "errand"

    Card(
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Trip type badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                val badgeColor = if (isErrand) MaterialTheme.colorScheme.tertiaryContainer
                else          MaterialTheme.colorScheme.primaryContainer
                val badgeText  = if (isErrand) MaterialTheme.colorScheme.onTertiaryContainer
                else          MaterialTheme.colorScheme.onPrimaryContainer
                Text(
                    if (isErrand) "Errand" else "Transport",
                    style      = MaterialTheme.typography.labelMedium,
                    color      = badgeText,
                    fontWeight = FontWeight.Bold,
                    modifier   = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(badgeColor)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    trip.vehicle_type,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(10.dp))

            // Errand note
            if (isErrand && !trip.note.isNullOrBlank()) {
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
                Spacer(Modifier.height(10.dp))
            }

            // Pickup
            Text("Jemput", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(trip.pickup_address, style = MaterialTheme.typography.bodyMedium)

            // Dropoff
            if (!trip.dropoff_address.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("Tujuan", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(trip.dropoff_address, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// ── Fare stepper ──────────────────────────────────────────────────────────────

@Composable
private fun FareStepperCard(viewModel: OfferPriceViewModel) {
    val currentFare  = viewModel.currentFare
    val defaultFare  = viewModel.defaultFare
    val isBelowFloor = viewModel.isBelowFloor

    // Raw string for the text field so the user can type freely
    var rawInput by remember { mutableStateOf(currentFare.roundToInt().toString()) }

    // Keep raw input in sync when +/- is tapped
    LaunchedEffect(currentFare) {
        val asInt = currentFare.roundToInt().toString()
        if (rawInput != asInt) rawInput = asInt
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier            = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            Text(
                "Harga Penawaran Anda",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(Modifier.height(4.dp))

            // Floor hint
            Text(
                "Minimum: ${formatRupiah(defaultFare)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (isBelowFloor) MaterialTheme.colorScheme.error
                else              MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))

            // ── +/- stepper row ───────────────────────────────────────────────
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier              = Modifier.fillMaxWidth()
            ) {
                // Minus button
                FilledTonalButton(
                    onClick  = {
                        viewModel.decrement()
                    },
                    enabled  = viewModel.canDecrement,
                    modifier = Modifier.size(52.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape    = RoundedCornerShape(12.dp)
                ) {
                    Text("−", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.width(16.dp))

                // Editable fare amount
                OutlinedTextField(
                    value         = rawInput,
                    onValueChange = { input ->
                        // Only allow digits
                        val filtered = input.filter { it.isDigit() }
                        rawInput = filtered
                        val parsed = filtered.toDoubleOrNull() ?: 0.0
                        viewModel.setFare(parsed)
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize   = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign  = TextAlign.Center,
                        color      = if (isBelowFloor) MaterialTheme.colorScheme.error
                        else              MaterialTheme.colorScheme.onSurface
                    ),
                    isError   = isBelowFloor,
                    singleLine = true,
                    modifier  = Modifier.width(160.dp),
                    prefix    = {
                        Text("Rp", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                )

                Spacer(Modifier.width(16.dp))

                // Plus button
                FilledTonalButton(
                    onClick        = { viewModel.increment() },
                    modifier       = Modifier.size(52.dp),
                    contentPadding = PaddingValues(0.dp),
                    shape          = RoundedCornerShape(12.dp)
                ) {
                    Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                "Ketuk + / − untuk ubah Rp 1.000",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Below-floor warning
            if (isBelowFloor) {
                Spacer(Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Harga tidak boleh di bawah minimum ${formatRupiah(defaultFare)}",
                        color    = MaterialTheme.colorScheme.onErrorContainer,
                        style    = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}

// ── Formatter ─────────────────────────────────────────────────────────────────

private fun formatRupiah(amount: Double): String {
    val fmt = NumberFormat.getNumberInstance(Locale("id", "ID"))
    return "Rp ${fmt.format(amount.roundToInt())}"
}