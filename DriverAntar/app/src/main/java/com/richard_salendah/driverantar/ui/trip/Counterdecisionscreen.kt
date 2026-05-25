package com.richard_salendah.driverantar.ui.trip

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CounterDecisionScreen(
    viewModel: CounterDecisionViewModel,
    onAccepted: (tripId: String) -> Unit,      // → WaitingForRider (rider must confirm)
    onCountered: (tripId: String, newFare: Double) -> Unit,  // → WaitingForRider
    onRejected: (message: String) -> Unit      // → IncomingTrips
) {
    val state = viewModel.state

    LaunchedEffect(state) {
        when (state) {
            is CounterDecisionState.Accepted  -> onAccepted(viewModel.tripId)
            is CounterDecisionState.Countered -> onCountered(viewModel.tripId, state.newFare)
            is CounterDecisionState.Rejected  ->
                onRejected("Anda menolak tawaran — trip dikembalikan ke antrean")
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Penawaran Balik dari Penumpang") }
                // No back nav — driver must make a decision
            )
        }
    ) { padding ->
        Column(
            modifier            = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // ── Rider's offer card ────────────────────────────────────────────
            Card(
                colors   = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier            = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.SwapVert,
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier           = Modifier.size(32.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Penumpang menawar",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        formatRupiah(viewModel.riderFare),
                        style      = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Minimum: ${formatRupiah(viewModel.defaultFare)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // ── Counter attempts remaining ────────────────────────────────────
            val remaining = viewModel.maxDriverCounters - viewModel.driverCounterCount
            if (viewModel.maxDriverCounters > 0) {
                Text(
                    if (viewModel.canCounter)
                        "Sisa kesempatan tawar Anda: $remaining dari ${viewModel.maxDriverCounters}"
                    else
                        "Kesempatan tawar Anda habis — terima atau tolak",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (viewModel.canCounter)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.error
                )
            }

            // ── Accept button ─────────────────────────────────────────────────
            Button(
                onClick  = { viewModel.acceptRiderOffer() },
                enabled  = state !is CounterDecisionState.Loading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors   = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Terima ${formatRupiah(viewModel.riderFare)}")
            }

            // ── Counter section (only if driver has attempts left) ─────────────
            if (viewModel.canCounter) {
                HorizontalDivider()

                Text(
                    "Atau ajukan harga balik",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Fare stepper
                FareStepperRow(viewModel = viewModel)

                if (viewModel.isBelowFloor) {
                    Text(
                        "Harga tidak boleh di bawah minimum ${formatRupiah(viewModel.defaultFare)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                FilledTonalButton(
                    onClick  = { viewModel.submitCounter() },
                    enabled  = !viewModel.isBelowFloor
                            && state !is CounterDecisionState.Loading,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("Ajukan Harga Balik")
                }
            }

            HorizontalDivider()

            // ── Reject button ─────────────────────────────────────────────────
            OutlinedButton(
                onClick  = { viewModel.rejectAndReset() },
                enabled  = state !is CounterDecisionState.Loading,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors   = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Tolak & Batalkan")
            }

            // ── Error ─────────────────────────────────────────────────────────
            if (state is CounterDecisionState.Error) {
                Card(
                    colors   = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier              = Modifier.padding(12.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(state.message,
                            color    = MaterialTheme.colorScheme.onErrorContainer,
                            style    = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearError() }) { Text("OK") }
                    }
                }
            }

            // Loading overlay
            if (state is CounterDecisionState.Loading) {
                CircularProgressIndicator()
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FareStepperRow(viewModel: CounterDecisionViewModel) {
    var rawInput by remember { mutableStateOf(viewModel.counterFare.roundToInt().toString()) }

    LaunchedEffect(viewModel.counterFare) {
        val asStr = viewModel.counterFare.roundToInt().toString()
        if (rawInput != asStr) rawInput = asStr
    }

    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier              = Modifier.fillMaxWidth()
    ) {
        FilledTonalButton(
            onClick        = { viewModel.decrement() },
            enabled        = viewModel.canDecrement,
            modifier       = Modifier.size(52.dp),
            contentPadding = PaddingValues(0.dp),
            shape          = RoundedCornerShape(12.dp)
        ) { Text("−", fontSize = 22.sp, fontWeight = FontWeight.Bold) }

        Spacer(Modifier.width(16.dp))

        OutlinedTextField(
            value         = rawInput,
            onValueChange = { input ->
                val filtered = input.filter { it.isDigit() }
                rawInput     = filtered
                viewModel.setFare(filtered.toDoubleOrNull() ?: 0.0)
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = LocalTextStyle.current.copy(
                fontSize   = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign  = TextAlign.Center,
                color      = if (viewModel.isBelowFloor)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurface
            ),
            isError    = viewModel.isBelowFloor,
            singleLine = true,
            modifier   = Modifier.width(160.dp),
            prefix     = {
                Text("Rp", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        )

        Spacer(Modifier.width(16.dp))

        FilledTonalButton(
            onClick        = { viewModel.increment() },
            modifier       = Modifier.size(52.dp),
            contentPadding = PaddingValues(0.dp),
            shape          = RoundedCornerShape(12.dp)
        ) { Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
    }
}

private fun formatRupiah(amount: Double): String {
    val fmt = NumberFormat.getNumberInstance(Locale("id", "ID"))
    return "Rp ${fmt.format(amount.roundToInt())}"
}