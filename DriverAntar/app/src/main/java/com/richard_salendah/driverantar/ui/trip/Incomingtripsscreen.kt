package com.richard_salendah.driverantar.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.richard_salendah.driverantar.data.model.IncomingTripResponse
import com.richard_salendah.driverantar.ui.components.OfflineBanner
import com.richard_salendah.driverantar.ui.components.SkeletonTripCard
import com.richard_salendah.driverantar.utils.ConnectivityObserver
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingTripsScreen(
    viewModel: IncomingTripsViewModel,
    navController: NavController,
    onBack: () -> Unit,
    onTripSelected: (trip: IncomingTripResponse) -> Unit
) {
    LaunchedEffect(Unit) { viewModel.startPolling() }
    DisposableEffect(Unit) { onDispose { viewModel.stopPolling() } }

    val isOnline by ConnectivityObserver.isOnline.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val vmSnack = viewModel.snackMessage
    LaunchedEffect(vmSnack) {
        if (vmSnack != null) {
            snackbarHostState.showSnackbar(vmSnack)
            viewModel.clearSnack()
        }
    }

    val backStackSnack = navController.currentBackStackEntry
        ?.savedStateHandle?.get<String>("snack")
    LaunchedEffect(backStackSnack) {
        if (!backStackSnack.isNullOrBlank()) {
            snackbarHostState.showSnackbar(backStackSnack)
            navController.currentBackStackEntry?.savedStateHandle?.remove<String>("snack")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title          = { Text("Incoming Trips") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->

        val trips     = viewModel.trips
        val isLoading = viewModel.isLoading
        val error     = viewModel.errorMessage

        Column(modifier = Modifier.padding(padding).fillMaxSize()) {

            // ── Offline banner ────────────────────────────────────────────────
            OfflineBanner(visible = !isOnline)

            when {
                // ── Skeleton on first load ────────────────────────────────────
                isLoading && trips.isEmpty() -> {
                    LazyColumn(
                        contentPadding      = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        userScrollEnabled   = false
                    ) {
                        items(3) { SkeletonTripCard() }
                    }
                }

                // ── Error ─────────────────────────────────────────────────────
                error != null && trips.isEmpty() -> {
                    Box(
                        Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Could not load trips",
                                style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick  = { viewModel.refresh() },
                                enabled  = isOnline
                            ) { Text("Try Again") }
                        }
                    }
                }

                // ── Empty ─────────────────────────────────────────────────────
                trips.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No trips nearby right now",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text("Refreshing automatically every 5 seconds",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // ── List ──────────────────────────────────────────────────────
                else -> {
                    LazyColumn(
                        modifier            = Modifier.fillMaxSize(),
                        contentPadding      = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                "${trips.size} trip${if (trips.size != 1) "s" else ""} available",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(trips, key = { it.id }) { trip ->
                            TripCard(
                                trip      = trip,
                                actionsEnabled = isOnline,
                                onClick   = { if (isOnline) onTripSelected(trip) }
                            )
                        }
                        item { Spacer(Modifier.height(72.dp)) }
                    }
                }
            }
        }
    }
}

// ── Trip card ─────────────────────────────────────────────────────────────────

@Composable
private fun TripCard(
    trip: IncomingTripResponse,
    actionsEnabled: Boolean,
    onClick: () -> Unit
) {
    val isErrand = trip.trip_type == "errand"

    Card(
        onClick   = onClick,
        enabled   = actionsEnabled,
        modifier  = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    TripTypeBadge(isErrand = isErrand)
                    Text(
                        trip.vehicle_type,
                        style    = MaterialTheme.typography.labelSmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                trip.distance_m?.let { distM ->
                    Text(
                        formatDistance(distM),
                        style      = MaterialTheme.typography.labelMedium,
                        color      = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            if (isErrand && !trip.note.isNullOrBlank()) {
                Card(
                    colors   = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer),
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

            AddressRow("Jemput", trip.pickup_address,
                MaterialTheme.colorScheme.primary)

            if (!trip.dropoff_address.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                AddressRow("Tujuan", trip.dropoff_address,
                    MaterialTheme.colorScheme.error)
            } else if (isErrand) {
                Spacer(Modifier.height(4.dp))
                Text("Tujuan: belum ditentukan",
                    style     = MaterialTheme.typography.bodySmall,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic)
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column {
                    Text("Harga minimum",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatRupiah(trip.default_fare),
                        style      = MaterialTheme.typography.titleMedium,
                        color      = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold)
                }
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        trip.payment_method.replaceFirstChar { it.uppercase() },
                        style    = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.tertiaryContainer)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        color    = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Button(
                        onClick        = onClick,
                        enabled        = actionsEnabled,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text("Tawar", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

// ── Shared small composables ──────────────────────────────────────────────────

@Composable
private fun TripTypeBadge(isErrand: Boolean) {
    val bg = if (isErrand) MaterialTheme.colorScheme.tertiaryContainer
    else          MaterialTheme.colorScheme.primaryContainer
    val fg = if (isErrand) MaterialTheme.colorScheme.onTertiaryContainer
    else          MaterialTheme.colorScheme.onPrimaryContainer
    Text(
        if (isErrand) "Errand" else "Transport",
        style      = MaterialTheme.typography.labelSmall,
        color      = fg,
        fontWeight = FontWeight.Bold,
        modifier   = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun AddressRow(label: String, address: String, tint: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.LocationOn, label,
            tint     = tint,
            modifier = Modifier.size(16.dp).padding(top = 1.dp))
        Spacer(Modifier.width(4.dp))
        Column {
            Text(label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(address, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        }
    }
}

private fun formatRupiah(amount: Double): String {
    val fmt = NumberFormat.getNumberInstance(Locale("id", "ID"))
    return "Rp ${fmt.format(amount.roundToInt())}"
}

private fun formatDistance(metres: Double): String = when {
    metres < 1_000 -> "${metres.roundToInt()} m"
    else           -> "${"%.1f".format(metres / 1_000)} km"
}