package com.richard_salendah.driverantar.ui.trip

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.richard_salendah.driverantar.data.model.IncomingTripResponse
import com.richard_salendah.driverantar.ui.components.OfflineBanner
import com.richard_salendah.driverantar.ui.components.SkeletonTripCard
import com.richard_salendah.driverantar.utils.ConnectivityObserver
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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
                                trip           = trip,
                                actionsEnabled = isOnline,
                                onClick        = { if (isOnline) onTripSelected(trip) }
                            )
                        }
                        item { Spacer(Modifier.height(72.dp)) }
                    }
                }
            }
        }
    }
}

// ── Candidate countdown ───────────────────────────────────────────────────────
// Mirrors the live process_trip_notification_timeouts() 3-minute candidate
// window (verified via Supabase MCP, not hardcoded from memory). Purely a
// local visual cue — no action is triggered at zero. The trip naturally
// disappears once the cron job (runs every 60s) actually reassigns it and
// the next 5s poll refreshes the list.

private const val CANDIDATE_WINDOW_SECONDS = 180
private const val FADE_WARNING_SECONDS     = 30   // last 30s — fade + countdown turns red

/** Parses a Postgres timestamptz::text value (space- or 'T'-separated, with/without offset) as UTC epoch millis. */
private fun parseUtcEpochMillis(raw: String): Long? = try {
    val cleaned = raw.replace(' ', 'T').substringBefore("+").substringBefore("Z").take(19)
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.parse(cleaned)?.time
} catch (e: Exception) { null }

/** Ticks every second. Returns null if [approvedAt] is null/unparseable (countdown simply isn't shown). */
@Composable
private fun rememberCandidateRemainingSeconds(approvedAt: String?): Int? {
    if (approvedAt == null) return null
    val approvedAtMillis = remember(approvedAt) { parseUtcEpochMillis(approvedAt) } ?: return null

    var remaining by remember(approvedAt) {
        mutableStateOf(
            (CANDIDATE_WINDOW_SECONDS - (System.currentTimeMillis() - approvedAtMillis) / 1000).toInt()
        )
    }
    LaunchedEffect(approvedAt) {
        // Stop ticking shortly after expiry — the card will be gone on the next poll anyway
        while (remaining > -2) {
            delay(1_000L)
            remaining = (CANDIDATE_WINDOW_SECONDS - (System.currentTimeMillis() - approvedAtMillis) / 1000).toInt()
        }
    }
    return remaining
}

private fun formatCountdown(seconds: Int): String {
    val clamped = seconds.coerceAtLeast(0)
    return "%d:%02d".format(clamped / 60, clamped % 60)
}

// ── Trip card ─────────────────────────────────────────────────────────────────

@Composable
private fun TripCard(
    trip: IncomingTripResponse,
    actionsEnabled: Boolean,
    onClick: () -> Unit
) {
    val isErrand         = trip.trip_type == "errand"
    val remainingSeconds = rememberCandidateRemainingSeconds(trip.candidate_approved_at)
    val expired          = remainingSeconds != null && remainingSeconds <= 0

    val cardAlpha by animateFloatAsState(
        targetValue = when {
            remainingSeconds == null || remainingSeconds > FADE_WARNING_SECONDS -> 1f
            else -> 0.4f + 0.6f * (remainingSeconds.coerceAtLeast(0) / FADE_WARNING_SECONDS.toFloat())
        },
        label = "tripCardFade"
    )

    Card(
        onClick   = onClick,
        enabled   = actionsEnabled && !expired,
        modifier  = Modifier.fillMaxWidth().alpha(cardAlpha),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Badge row + countdown ─────────────────────────────────────────
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
                Column(horizontalAlignment = Alignment.End) {
                    trip.distance_m?.let { distM ->
                        Text(
                            formatDistance(distM),
                            style      = MaterialTheme.typography.labelMedium,
                            color      = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    remainingSeconds?.let { rem ->
                        Text(
                            text  = if (rem > 0) formatCountdown(rem) else "Kedaluwarsa",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (rem <= FADE_WARNING_SECONDS)
                                MaterialTheme.colorScheme.error
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── Rider avatar + name ───────────────────────────────────────────
            RiderAvatarRow(
                avatarUrl = trip.rider_avatar_url,
                name      = trip.rider_name
            )

            Spacer(Modifier.height(10.dp))

            // ── Errand instruction card ───────────────────────────────────────
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

            // ── Addresses ─────────────────────────────────────────────────────
            AddressRow("Jemput", trip.pickup_address, MaterialTheme.colorScheme.primary)

            if (!trip.dropoff_address.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                AddressRow("Tujuan", trip.dropoff_address, MaterialTheme.colorScheme.error)
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

            // ── Footer: fare + payment + action button ────────────────────────
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
                        enabled        = actionsEnabled && !expired,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(
                            if (expired) "Kedaluwarsa" else "Tawar",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}

// ── Rider avatar row ──────────────────────────────────────────────────────────

/**
 * Small circular avatar + rider name shown on the TripCard.
 * Falls back to a generic person icon when [avatarUrl] is null (new rider
 * who hasn't uploaded a photo yet). Coil cache disabled — same policy as
 * ProfileScreen — so a recently changed avatar isn't stale.
 */
@Composable
private fun RiderAvatarRow(avatarUrl: String?, name: String) {
    val context = LocalContext.current
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (avatarUrl != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(avatarUrl)
                    .crossfade(true)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .build(),
                contentDescription = "Foto penumpang",
                contentScale       = ContentScale.Crop,
                modifier           = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        } else {
            Box(
                modifier         = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Text(
            text  = name.ifBlank { "Penumpang" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
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