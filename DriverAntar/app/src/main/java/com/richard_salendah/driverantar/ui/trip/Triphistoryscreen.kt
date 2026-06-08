package com.richard_salendah.driverantar.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.richard_salendah.driverantar.data.model.DriverTripResponse
import com.richard_salendah.driverantar.ui.components.OfflineBanner
import com.richard_salendah.driverantar.ui.components.SkeletonHistoryCard
import com.richard_salendah.driverantar.utils.ConnectivityObserver
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripHistoryScreen(
    viewModel: TripHistoryViewModel,
    onBack: () -> Unit,
    onRateTrip: (tripId: String) -> Unit
) {
    val listState = rememberLazyListState()
    val isOnline  by ConnectivityObserver.isOnline.collectAsState()

    val shouldLoadMore = remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems  = listState.layoutInfo.totalItemsCount
            lastVisible >= totalItems - 4
        }
    }
    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) viewModel.loadMore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title          = { Text("Riwayat Perjalanan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
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
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        userScrollEnabled   = false
                    ) {
                        items(5) { SkeletonHistoryCard() }
                    }
                }

                // ── Error ─────────────────────────────────────────────────────
                error != null && trips.isEmpty() -> {
                    Box(
                        modifier         = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Gagal memuat riwayat",
                                style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick  = { viewModel.load() },
                                enabled  = isOnline
                            ) { Text("Coba Lagi") }
                        }
                    }
                }

                // ── Empty ─────────────────────────────────────────────────────
                trips.isEmpty() -> {
                    Box(
                        modifier         = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Belum ada perjalanan",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text("Perjalanan yang selesai akan muncul di sini",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                // ── List ──────────────────────────────────────────────────────
                else -> {
                    PullToRefreshBox(
                        isRefreshing = isLoading,
                        onRefresh    = { if (isOnline) viewModel.load() },
                        modifier     = Modifier.fillMaxSize()
                    ) {
                        LazyColumn(
                            state               = listState,
                            contentPadding      = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(trips, key = { it.id }) { trip ->
                                TripHistoryCard(
                                    trip       = trip,
                                    onRateTrip = { onRateTrip(trip.id) }
                                )
                            }
                            if (viewModel.isLoadingMore) {
                                item {
                                    Box(
                                        modifier         = Modifier.fillMaxWidth().padding(12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            Modifier.size(24.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                            if (!viewModel.hasMore && trips.isNotEmpty()) {
                                item {
                                    Text(
                                        "Semua perjalanan sudah ditampilkan",
                                        style    = MaterialTheme.typography.labelSmall,
                                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp)
                                            .wrapContentWidth(Alignment.CenterHorizontally)
                                    )
                                }
                            }
                            item { Spacer(Modifier.height(72.dp)) }
                        }
                    }
                }
            }
        }
    }
}

// ── Trip history card (unchanged logic, no haptic needed here) ────────────────

@Composable
private fun TripHistoryCard(trip: DriverTripResponse, onRateTrip: () -> Unit) {
    val isCompleted  = trip.status == "completed"
    val alreadyRated = trip.rider_rating_given != null

    Card(
        modifier  = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    TripTypePill(tripType = trip.trip_type)
                    StatusPill(status = trip.status)
                }
                Text(formatDate(trip.created_at),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Text(trip.pickup_address,
                style    = MaterialTheme.typography.bodyMedium, maxLines = 2)
            if (!trip.dropoff_address.isNullOrBlank()) {
                Text("→ ${trip.dropoff_address}",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2)
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(10.dp))
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                if (trip.status == "cancelled") {
                    Text("Dibatalkan",
                        style      = MaterialTheme.typography.bodyMedium,
                        color      = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold)
                } else {
                    Text(formatRupiah(trip.fare ?: trip.offered_fare ?: 0.0),
                        style      = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.primary)
                }
                when {
                    alreadyRated -> {
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(Icons.Filled.Star, null,
                                tint     = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp))
                            Text("${trip.rider_rating_given}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    isCompleted -> {
                        FilledTonalButton(
                            onClick        = onRateTrip,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Outlined.StarOutline, null,
                                modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Nilai", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TripTypePill(tripType: String) {
    val isErrand = tripType == "errand"
    Text(
        if (isErrand) "Errand" else "Transport",
        style      = MaterialTheme.typography.labelSmall,
        color      = if (isErrand) MaterialTheme.colorScheme.onTertiaryContainer
        else          MaterialTheme.colorScheme.onPrimaryContainer,
        fontWeight = FontWeight.Bold,
        modifier   = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (isErrand) MaterialTheme.colorScheme.tertiaryContainer
            else          MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun StatusPill(status: String) {
    val (bg, fg, label) = when (status) {
        "completed" -> Triple(MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer, "Selesai")
        "cancelled" -> Triple(MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer, "Dibatalkan")
        else        -> Triple(MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant, status)
    }
    Text(label,
        style    = MaterialTheme.typography.labelSmall,
        color    = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 3.dp))
}

private fun formatRupiah(amount: Double): String {
    val fmt = NumberFormat.getNumberInstance(Locale("id", "ID"))
    return "Rp ${fmt.format(amount.roundToInt())}"
}

private val inputFmt  = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
private val outputFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID"))

private fun formatDate(raw: String): String = try {
    val cleaned = raw.substringBefore("+").substringBefore("Z").take(19)
    val date    = inputFmt.parse(cleaned)
    if (date != null) outputFmt.format(date) else raw
} catch (e: Exception) { raw }