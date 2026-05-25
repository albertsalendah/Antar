package com.richard_salendah.antar.ui.trip

import android.app.Application
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.richard_salendah.antar.Antar
import com.richard_salendah.antar.data.model.TripResponse
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

// ── ViewModel ─────────────────────────────────────────────────────────────────

class TripCompleteViewModel(app: Application) : AndroidViewModel(app) {

    private val api = (app as Antar).apiService

    var trip    by mutableStateOf<TripResponse?>(null)
    var loading by mutableStateOf(false)
    var error   by mutableStateOf<String?>(null)

    fun load(tripId: String) {
        if (trip != null) return
        viewModelScope.launch {
            loading = true
            runCatching {
                val resp = api.getTrip(tripId)
                if (resp.isSuccessful) trip = resp.body()?.data
                else error = "Gagal memuat detail perjalanan"
            }.onFailure { error = "Tidak dapat terhubung ke server" }
            loading = false
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

private val PrimaryBlue = Color(0xFF1B6CA8)
private val Green       = Color(0xFF2E7D32)
private val GreenLight  = Color(0xFFE8F5E9)
private val Amber       = Color(0xFFFFA000)

@Composable
fun TripCompleteScreen(
    tripId: String,
    onRate: () -> Unit,
    onSkip: () -> Unit,
    viewModel: TripCompleteViewModel = viewModel(),
) {
    LaunchedEffect(Unit) { viewModel.load(tripId) }

    val trip = viewModel.trip

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F6F9))
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))

            // ── Success icon ──────────────────────────────────────────────────
            Surface(
                shape  = CircleShape,
                color  = GreenLight,
                modifier = Modifier.size(96.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint     = Green,
                        modifier = Modifier.size(56.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "Perjalanan Selesai!",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color      = Color(0xFF1A1A1A),
                ),
            )
            Text(
                "Terima kasih telah menggunakan Antar",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color     = Color(0xFF888888),
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(32.dp))

            when {
                viewModel.loading -> CircularProgressIndicator(color = PrimaryBlue)
                viewModel.error != null -> Text(
                    viewModel.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                trip != null -> TripSummaryCard(trip)
            }

            Spacer(Modifier.height(24.dp))

            // ── Rate prompt ───────────────────────────────────────────────────
            Card(
                modifier  = Modifier.fillMaxWidth(),
                shape     = RoundedCornerShape(16.dp),
                colors    = CardDefaults.cardColors(containerColor = Color(0xFFFFFDE7)),
                elevation = CardDefaults.cardElevation(0.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.Star, null,
                        tint     = Amber,
                        modifier = Modifier.size(28.dp),
                    )
                    Column {
                        Text(
                            "Bagaimana perjalanan Anda?",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                                color      = Color(0xFF5D4037),
                            ),
                        )
                        Text(
                            "Beri rating untuk membantu driver mendapatkan lebih banyak penumpang",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = Color(0xFF8D6E63),
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        // ── Buttons ───────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick  = onRate,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Amber),
            ) {
                Icon(Icons.Default.Star, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Beri Rating Driver",
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            OutlinedButton(
                onClick  = onSkip,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF888888)),
            ) {
                Text("Lewati", fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ── Trip summary card ─────────────────────────────────────────────────────────

@Composable
private fun TripSummaryCard(trip: TripResponse) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(16.dp),
        colors    = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Fare row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(shape = CircleShape, color = GreenLight) {
                        Icon(
                            Icons.Default.Payments, null,
                            tint     = Green,
                            modifier = Modifier.padding(6.dp).size(16.dp),
                        )
                    }
                    Column {
                        Text(
                            "Total Ongkos",
                            style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF999999)),
                        )
                        Text(
                            formatRupiah(trip.fare ?: trip.offeredFare ?: 0.0),
                            style = MaterialTheme.typography.titleMedium.copy(
                                color      = Green,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFF0F0F0),
                ) {
                    Text(
                        "Tunai",
                        style    = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF666666)),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFFF0F0F0))

            SummaryRow(
                icon     = Icons.Default.LocationOn,
                iconTint = PrimaryBlue,
                label    = "Penjemputan",
                value    = trip.pickupAddress,
            )

            if (trip.tripType == "transport" && !trip.dropoffAddress.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                SummaryRow(
                    icon     = Icons.Default.LocationOn,
                    iconTint = Color(0xFFE53935),
                    label    = "Tujuan",
                    value    = trip.dropoffAddress!!,
                )
            } else if (trip.tripType == "errand" && !trip.note.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                SummaryRow(
                    icon     = Icons.Default.Payments,
                    iconTint = Color(0xFF777777),
                    label    = "Keterangan",
                    value    = trip.note!!,
                )
            }
        }
    }
}

@Composable
private fun SummaryRow(icon: ImageVector, iconTint: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFAAAAAA)))
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color(0xFF1A1A1A), fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

private fun formatRupiah(amount: Double) =
    "Rp " + NumberFormat.getNumberInstance(Locale("id", "ID")).format(amount.toLong())