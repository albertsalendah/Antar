package com.richard_salendah.antar.ui.trip

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

private val PrimaryBlue  = Color(0xFF1B6CA8)
private val PrimaryAlpha = Color(0x261B6CA8) // 15 % blue

@Composable
fun SearchingScreen(
    tripId: String,
    onOfferReceived: () -> Unit,
    onTripCancelled: () -> Unit,
    viewModel: SearchingViewModel = viewModel(),
) {
    var showCancelDialog by remember { mutableStateOf(false) }

    // Start watching once — ViewModel guards against duplicate calls
    LaunchedEffect(Unit) {
        viewModel.startWatching(tripId, onOfferReceived, onTripCancelled)
    }

    // Cancel confirmation dialog
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = { Text("Batalkan Pesanan?") },
            text  = { Text("Driver belum menerima pesanan Anda. Yakin ingin membatalkan?") },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelDialog = false
                        viewModel.cancelTrip(tripId, onTripCancelled)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                ) { Text("Ya, Batalkan") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) { Text("Tidak") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {

        Spacer(Modifier.height(48.dp))

        // Title
        Text(
            "Mencari Driver",
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                color      = Color(0xFF1A1A1A),
            ),
        )
        Text(
            "Harap tunggu, kami sedang mencarikan driver terdekat untuk Anda",
            style = MaterialTheme.typography.bodyMedium.copy(
                color     = Color(0xFF777777),
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.padding(horizontal = 40.dp, vertical = 8.dp),
        )

        Spacer(Modifier.height(40.dp))

        // ── Radar animation ───────────────────────────────────────────────────
        RadarAnimation(modifier = Modifier.size(220.dp))

        Spacer(Modifier.height(40.dp))

        // ── Trip summary card ─────────────────────────────────────────────────
        Card(
            modifier  = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape     = RoundedCornerShape(16.dp),
            colors    = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(2.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Detail Pesanan",
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = Color(0xFF999999), fontWeight = FontWeight.SemiBold,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        Icons.Default.LocationOn, null,
                        tint     = PrimaryBlue,
                        modifier = Modifier.size(18.dp),
                    )
                    Column {
                        Text(
                            "ID Pesanan",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color(0xFFAAAAAA),
                            ),
                        )
                        Text(
                            // Show last 8 chars of tripId so it's readable
                            tripId.takeLast(8).uppercase(),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color      = Color(0xFF1A1A1A),
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFFF0F0F0),
                    ) {
                        Text(
                            "●●●",
                            style    = MaterialTheme.typography.labelSmall.copy(
                                color = Color(0xFF888888),
                            ),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                    Text(
                        "Menunggu konfirmasi driver…",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = Color(0xFF777777),
                        ),
                    )
                }
            }
        }

        // Cancel error
        if (viewModel.cancelError != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                viewModel.cancelError!!,
                color  = MaterialTheme.colorScheme.error,
                style  = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        // ── Cancel button ─────────────────────────────────────────────────────
        OutlinedButton(
            onClick  = { showCancelDialog = true },
            enabled  = !viewModel.cancelLoading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .height(52.dp),
            shape  = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE53935)),
        ) {
            if (viewModel.cancelLoading) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color       = Color(0xFFE53935),
                )
            } else {
                Text(
                    "Batalkan Pesanan",
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// ── Radar animation ───────────────────────────────────────────────────────────
// Three rings pulse outward with staggered offsets, fading as they expand.

@Composable
private fun RadarAnimation(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "radar")

    // Each ring has a different delay so they stagger evenly across 2.4 s
    val scale1 by transition.animateFloat(
        initialValue   = 0f, targetValue = 1f,
        animationSpec  = infiniteRepeatable(
            animation  = tween(2_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ), label = "ring1",
    )
    val scale2 by transition.animateFloat(
        initialValue   = 0f, targetValue = 1f,
        animationSpec  = infiniteRepeatable(
            animation  = tween(2_400, 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ), label = "ring2",
    )
    val scale3 by transition.animateFloat(
        initialValue   = 0f, targetValue = 1f,
        animationSpec  = infiniteRepeatable(
            animation  = tween(2_400, 1_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ), label = "ring3",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRing(scale1)
            drawRing(scale2)
            drawRing(scale3)
        }

        // Centre car icon on a solid blue disc
        Surface(
            shape = CircleShape,
            color = PrimaryBlue,
            modifier = Modifier.size(68.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(
                    Icons.Default.DirectionsCar,
                    contentDescription = "Mencari driver",
                    tint     = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
    }
}

private fun DrawScope.drawRing(progress: Float) {
    val maxRadius = size.minDimension / 2f
    val radius    = maxRadius * progress
    val alpha     = (1f - progress).coerceIn(0f, 1f)

    // Filled translucent disc
    drawCircle(
        color  = Color(0x1A1B6CA8),
        radius = radius,
        alpha  = alpha,
    )
    // Ring stroke
    drawCircle(
        color  = PrimaryBlue,
        radius = radius,
        style  = Stroke(width = 2.dp.toPx()),
        alpha  = alpha * 0.6f,
    )
}