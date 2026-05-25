package com.richard_salendah.antar.ui.trip

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.richard_salendah.antar.Antar
import com.richard_salendah.antar.data.model.RateRequest
import com.richard_salendah.antar.ui.common.HapticType
import com.richard_salendah.antar.ui.common.rememberHaptic
import kotlinx.coroutines.launch

// ── ViewModel ─────────────────────────────────────────────────────────────────

class RateDriverViewModel(app: Application) : AndroidViewModel(app) {

    private val api = (app as Antar).apiService

    var score   by mutableIntStateOf(0)
    var comment by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error   by mutableStateOf<String?>(null)

    fun submit(tripId: String, onDone: () -> Unit) {
        if (score == 0) { error = "Pilih bintang terlebih dahulu"; return }
        viewModelScope.launch {
            loading = true
            error   = null
            runCatching {
                val resp = api.rateDriver(tripId, RateRequest(score, comment.trim().ifEmpty { null }))
                if (resp.isSuccessful) {
                    onDone()
                } else {
                    error = runCatching {
                        org.json.JSONObject(resp.errorBody()?.string() ?: "")
                            .optString("error").takeIf { it.isNotEmpty() }
                    }.getOrNull() ?: "Gagal mengirim rating, coba lagi"
                }
            }.onFailure { error = "Tidak dapat terhubung ke server" }
            loading = false
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

private val Amber       = Color(0xFFFFA000)
private val AmberLight  = Color(0xFFFFF8E1)
private val PrimaryBlue = Color(0xFF1B6CA8)
private val starLabels  = listOf("Sangat Buruk", "Buruk", "Cukup", "Bagus", "Luar Biasa!")

@Composable
fun RateDriverScreen(
    tripId: String,
    onDone: () -> Unit,
    viewModel: RateDriverViewModel = viewModel(),
) {
    val haptic = rememberHaptic()

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
            Spacer(Modifier.height(48.dp))

            Surface(shape = CircleShape, color = Color(0xFFE8F4FD), modifier = Modifier.size(88.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.Person, null, tint = PrimaryBlue, modifier = Modifier.size(48.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            Text("Bagaimana driver Anda?",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold, color = Color(0xFF1A1A1A)))
            Text("Rating Anda membantu menjaga kualitas layanan",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = Color(0xFF888888), textAlign = TextAlign.Center),
                modifier = Modifier.padding(top = 6.dp, bottom = 32.dp))

            // ── Star selector ─────────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                (1..5).forEach { star ->
                    val filled = star <= viewModel.score
                    Icon(
                        imageVector = if (filled) Icons.Default.Star else Icons.Outlined.StarOutline,
                        contentDescription = "$star bintang",
                        tint     = if (filled) Amber else Color(0xFFCCCCCC),
                        modifier = Modifier
                            .size(48.dp)
                            .clickable {
                                haptic.perform(HapticType.Tick) // ← haptic on star tap
                                viewModel.score = star
                                viewModel.error = null
                            },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            if (viewModel.score > 0) {
                Surface(shape = RoundedCornerShape(20.dp), color = AmberLight) {
                    Text(starLabels[viewModel.score - 1],
                        style    = MaterialTheme.typography.labelLarge.copy(
                            color = Amber, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
            } else {
                Text("Ketuk bintang untuk memberi nilai",
                    style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFFAAAAAA)))
            }

            Spacer(Modifier.height(28.dp))

            OutlinedTextField(
                value         = viewModel.comment,
                onValueChange = { viewModel.comment = it; viewModel.error = null },
                label         = { Text("Komentar (opsional)") },
                placeholder   = { Text("Ceritakan pengalaman Anda…") },
                singleLine    = false,
                minLines      = 3,
                maxLines      = 5,
                modifier      = Modifier.fillMaxWidth(),
                shape         = RoundedCornerShape(12.dp),
            )

            if (viewModel.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(viewModel.error!!, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(16.dp))
        }

        // ── Bottom buttons ────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    viewModel.submit(tripId) {
                        haptic.perform(HapticType.Confirm) // ← haptic on successful submit
                        onDone()
                    }
                },
                enabled  = !viewModel.loading && viewModel.score > 0,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = RoundedCornerShape(12.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Amber),
            ) {
                if (viewModel.loading) {
                    CircularProgressIndicator(color = Color.White,
                        modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                } else {
                    Text("Kirim Rating", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Lewati", color = Color(0xFF888888), fontWeight = FontWeight.Medium)
            }
        }
    }
}