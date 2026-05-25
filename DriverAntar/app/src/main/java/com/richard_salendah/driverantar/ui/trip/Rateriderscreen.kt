package com.richard_salendah.driverantar.ui.trip

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RateRiderScreen(
    viewModel: RateRiderViewModel,
    /** Called after submit OR skip — navigate to Map */
    onDone: () -> Unit
) {
    val uiState = viewModel.uiState

    LaunchedEffect(uiState) {
        if (uiState is RateRiderUiState.Done) onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nilai Penumpang") }
                // No back — driver either submits or skips explicitly
            )
        }
    ) { padding ->
        Column(
            modifier            = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            // ── Header ────────────────────────────────────────────────────────
            Text(
                "Perjalanan selesai! 🎉",
                style     = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                "Bagaimana pengalaman Anda dengan penumpang ini?",
                style     = MaterialTheme.typography.bodyMedium,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            // ── Star selector ─────────────────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StarSelector(
                    selectedScore = viewModel.selectedScore,
                    onStarTapped  = { viewModel.selectScore(it) }
                )
                // Label under stars
                Text(
                    scoreLabel(viewModel.selectedScore),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (viewModel.selectedScore > 0)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // ── Comment field (optional) ──────────────────────────────────────
            OutlinedTextField(
                value         = viewModel.comment,
                onValueChange = { viewModel.settComment(it) },
                label         = { Text("Komentar (opsional)") },
                placeholder   = { Text("Tulis sesuatu tentang penumpang ini…") },
                minLines      = 3,
                maxLines      = 5,
                modifier      = Modifier.fillMaxWidth()
            )

            // ── Error ─────────────────────────────────────────────────────────
            if (uiState is RateRiderUiState.Error) {
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
                        Text(
                            uiState.message,
                            color    = MaterialTheme.colorScheme.onErrorContainer,
                            style    = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.clearError() }) { Text("OK") }
                    }
                }
            }

            // ── Submit button ─────────────────────────────────────────────────
            Button(
                onClick  = { viewModel.submitRating() },
                enabled  = viewModel.canSubmit
                        && uiState !is RateRiderUiState.Loading,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (uiState is RateRiderUiState.Loading) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color       = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Kirim Penilaian", style = MaterialTheme.typography.titleMedium)
                }
            }

            // ── Skip button ───────────────────────────────────────────────────
            TextButton(
                onClick  = { viewModel.skip() },
                enabled  = uiState !is RateRiderUiState.Loading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Lewati",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Star selector composable ──────────────────────────────────────────────────

@Composable
private fun StarSelector(
    selectedScore: Int,
    onStarTapped: (Int) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        for (star in 1..5) {
            val filled = star <= selectedScore
            Icon(
                imageVector        = if (filled) Icons.Filled.Star
                else        Icons.Outlined.StarOutline,
                contentDescription = "Star $star",
                tint               = if (filled) MaterialTheme.colorScheme.primary
                else        MaterialTheme.colorScheme.onSurfaceVariant,
                modifier           = Modifier
                    .size(48.dp)
                    .clickable { onStarTapped(star) }
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun scoreLabel(score: Int): String = when (score) {
    1    -> "Sangat Buruk ⭐"
    2    -> "Buruk ⭐⭐"
    3    -> "Cukup ⭐⭐⭐"
    4    -> "Baik ⭐⭐⭐⭐"
    5    -> "Sangat Baik ⭐⭐⭐⭐⭐"
    else -> "Pilih bintang di atas"
}