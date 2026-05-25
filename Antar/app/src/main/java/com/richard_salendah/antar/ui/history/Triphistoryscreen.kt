package com.richard_salendah.antar.ui.history

import android.app.Application
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.richard_salendah.antar.Antar
import com.richard_salendah.antar.data.model.TripResponse
import com.richard_salendah.antar.ui.common.TripCardSkeleton
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// ── ViewModel ─────────────────────────────────────────────────────────────────

class TripHistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val api = (app as Antar).apiService

    var trips      by mutableStateOf<List<TripResponse>>(emptyList())
    var loading    by mutableStateOf(false)
    var refreshing by mutableStateOf(false)
    var hasMore    by mutableStateOf(true)
    var error      by mutableStateOf<String?>(null)

    private val pageSize = 20
    private var offset   = 0

    init { load() }

    fun load() {
        if (loading || !hasMore) return
        viewModelScope.launch {
            loading = true
            error   = null
            runCatching {
                val resp = api.listTrips(limit = pageSize, offset = offset)
                if (resp.isSuccessful) {
                    val page = resp.body()?.data ?: emptyList()
                    trips   = trips + page
                    offset += page.size
                    hasMore = page.size == pageSize
                } else {
                    error = "Gagal memuat riwayat"
                }
            }.onFailure { error = "Tidak dapat terhubung ke server" }
            loading = false
        }
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing = true
            error      = null
            offset     = 0
            hasMore    = true
            runCatching {
                val resp = api.listTrips(limit = pageSize, offset = 0)
                if (resp.isSuccessful) {
                    val page = resp.body()?.data ?: emptyList()
                    trips   = page
                    offset  = page.size
                    hasMore = page.size == pageSize
                } else {
                    error = "Gagal memuat riwayat"
                }
            }.onFailure { error = "Tidak dapat terhubung ke server" }
            refreshing = false
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

private val PrimaryBlue = Color(0xFF1B6CA8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripHistoryScreen(
    onBack: () -> Unit,
    onRateTrip: (tripId: String) -> Unit = {},
    viewModel: TripHistoryViewModel = viewModel(),
) {
    val listState = rememberLazyListState()

    LaunchedEffect(listState) {
        snapshotFlow {
            val info  = listState.layoutInfo
            val total = info.totalItemsCount
            val last  = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && last >= total - 3
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { viewModel.load() }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF4F6F9))) {

        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .statusBarsPadding()
                .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = PrimaryBlue)
            }
            Text(
                "Riwayat Perjalanan",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A),
                ),
            )
        }

        PullToRefreshBox(
            isRefreshing = viewModel.refreshing,
            onRefresh    = { viewModel.refresh() },
            modifier     = Modifier.weight(1f),
        ) {
            when {
                // ── Shimmer on first load ─────────────────────────────────────
                viewModel.loading && viewModel.trips.isEmpty() -> {
                    LazyColumn(
                        modifier        = Modifier.fillMaxSize(),
                        contentPadding  = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(6) { TripCardSkeleton() }
                    }
                }

                // ── Empty state ───────────────────────────────────────────────
                !viewModel.loading && viewModel.trips.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.DirectionsCar, null,
                                tint = Color(0xFFCCCCCC), modifier = Modifier.size(64.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Belum ada perjalanan",
                                style = MaterialTheme.typography.titleMedium.copy(color = Color(0xFF999999)))
                            Text("Perjalanan yang sudah selesai\nakan muncul di sini",
                                style     = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFBBBBBB)),
                                textAlign = TextAlign.Center,
                                modifier  = Modifier.padding(top = 4.dp))
                        }
                    }
                }

                // ── List ──────────────────────────────────────────────────────
                else -> {
                    LazyColumn(
                        state               = listState,
                        modifier            = Modifier.fillMaxSize(),
                        contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(viewModel.trips, key = { it.id }) { trip ->
                            TripHistoryCard(trip = trip, onRateTrip = { onRateTrip(trip.id) })
                        }
                        item {
                            when {
                                viewModel.loading ->
                                    Box(Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = PrimaryBlue,
                                            modifier = Modifier.size(28.dp))
                                    }
                                viewModel.error != null ->
                                    Row(Modifier.fillMaxWidth().padding(8.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text(viewModel.error!!, color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall)
                                        Spacer(Modifier.width(8.dp))
                                        TextButton(onClick = { viewModel.load() }) {
                                            Text("Coba lagi", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                !viewModel.hasMore && viewModel.trips.isNotEmpty() ->
                                    Box(Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center) {
                                        Text("Semua riwayat telah dimuat",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = Color(0xFFBBBBBB)))
                                    }
                            }
                        }
                        item { Spacer(Modifier.navigationBarsPadding()) }
                    }
                }
            }
        }
    }
}

// ── Trip card ─────────────────────────────────────────────────────────────────

@Composable
private fun TripHistoryCard(trip: TripResponse, onRateTrip: () -> Unit) {
    val isCancelled = trip.status == "cancelled"
    val canRate     = !isCancelled && !trip.riderHasRated

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(14.dp),
        colors    = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {

            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(6.dp),
                    color = if (trip.tripType == "transport") Color(0xFFE8F4FD) else Color(0xFFF3E5F5)) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(
                            if (trip.tripType == "transport") Icons.Default.DirectionsCar
                            else Icons.Default.ShoppingCart,
                            null,
                            tint = if (trip.tripType == "transport") PrimaryBlue else Color(0xFF7B1FA2),
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            if (trip.tripType == "transport") "Antar" else "Errand",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (trip.tripType == "transport") PrimaryBlue else Color(0xFF7B1FA2),
                                fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
                Surface(shape = RoundedCornerShape(6.dp),
                    color = if (isCancelled) Color(0xFFFFEBEE) else Color(0xFFE8F5E9)) {
                    Text(
                        if (isCancelled) "Dibatalkan" else "Selesai",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (isCancelled) Color(0xFFE53935) else Color(0xFF2E7D32),
                            fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.LocationOn, null, tint = PrimaryBlue,
                    modifier = Modifier.size(15.dp).padding(top = 1.dp))
                Text(trip.pickupAddress,
                    style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF444444)),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (trip.tripType == "transport" && !trip.dropoffAddress.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.LocationOn, null, tint = Color(0xFFE53935),
                        modifier = Modifier.size(15.dp).padding(top = 1.dp))
                    Text(trip.dropoffAddress!!,
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF444444)),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } else if (trip.tripType == "errand" && !trip.note.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(trip.note!!,
                    style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF777777)),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 21.dp))
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Color(0xFFF0F0F0))

            Row(modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        if (isCancelled) "—" else formatRupiah(trip.fare ?: trip.offeredFare ?: 0.0),
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = if (isCancelled) Color(0xFFBBBBBB) else PrimaryBlue,
                            fontWeight = FontWeight.Bold),
                    )
                    Text(formatDate(trip.createdAt),
                        style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFAAAAAA)))
                }
                if (canRate) {
                    Button(
                        onClick  = onRateTrip,
                        modifier = Modifier.height(34.dp),
                        shape    = RoundedCornerShape(8.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFFF8E1),
                            contentColor   = Color(0xFFFFA000)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) {
                        Icon(Icons.Default.Star, null, modifier = Modifier.size(14.dp), tint = Color(0xFFFFA000))
                        Spacer(Modifier.width(4.dp))
                        Text("Nilai", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else if (!isCancelled) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Default.Star, null, tint = Color(0xFFFFA000), modifier = Modifier.size(14.dp))
                        Text("Sudah dinilai",
                            style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFAAAAAA)))
                    }
                }
            }
        }
    }
}

private fun formatRupiah(amount: Double) =
    "Rp " + NumberFormat.getNumberInstance(Locale("id", "ID")).format(amount.toLong())

private fun formatDate(iso: String): String = runCatching {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("Asia/Makassar")
    }
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale("id", "ID")).format(parser.parse(iso)!!)
}.getOrDefault(iso)