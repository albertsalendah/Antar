package com.richard_salendah.antar.ui.booking

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ElectricRickshaw
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.richard_salendah.antar.data.model.VehicleTypeResponse

private val PrimaryBlue   = Color(0xFF1B6CA8)
private val SelectedBg    = Color(0xFFE8F4FD)
private val SelectedBorder = Color(0xFF1B6CA8)
private val UnselectedBg  = Color(0xFFF9F9F9)

@Composable
fun VehicleTypePickerScreen(
    onVehicleSelected: () -> Unit,
    onBack: () -> Unit,
) {
    val context  = LocalContext.current
    val activity = context as ComponentActivity
    val vm: BookingViewModel = viewModel(activity)

    var validationError by remember { mutableStateOf<String?>(null) }

    // ── Seed GPS coords into the ViewModel on first entry ─────────────────────
    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            try {
                LocationServices.getFusedLocationProviderClient(context)
                    .lastLocation
                    .addOnSuccessListener { loc ->
                        loc?.let { vm.seedLocation(it.latitude, it.longitude) }
                    }
            } catch (_: SecurityException) {}
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Top bar ───────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = PrimaryBlue)
            }
            Text(
                "Pesan Kendaraan",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A),
                ),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {

            Spacer(Modifier.height(8.dp))

            // ── Trip type toggle ──────────────────────────────────────────────
            SectionLabel("Tipe Perjalanan")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TripTypeChip(
                    label    = "Antar",
                    subLabel = "Jemput & antar ke tujuan",
                    icon     = Icons.Default.DirectionsCar,
                    selected = vm.tripType == "transport",
                    modifier = Modifier.weight(1f),
                    onClick  = {
                        vm.tripType = "transport"
                        validationError = null
                    },
                )
                TripTypeChip(
                    label    = "Errand",
                    subLabel = "Titip belanja atau keperluan",
                    icon     = Icons.Default.ShoppingCart,
                    selected = vm.tripType == "errand",
                    modifier = Modifier.weight(1f),
                    onClick  = {
                        vm.tripType = "errand"
                        validationError = null
                    },
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Vehicle type cards ────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SectionLabel("Jenis Kendaraan")
                if (vm.typesError != null) {
                    TextButton(onClick = { vm.loadVehicleTypes() }) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Coba lagi", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            when {
                vm.typesLoading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = PrimaryBlue) }
                }
                vm.typesError != null -> {
                    Text(
                        vm.typesError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                else -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        vm.vehicleTypes.forEach { vt ->
                            VehicleCard(
                                vehicleType = vt,
                                selected    = vm.selectedType?.id == vt.id,
                                modifier    = Modifier.weight(1f),
                                onClick     = {
                                    vm.selectedType = vt
                                    validationError = null
                                },
                            )
                        }
                        // Fill remaining space if < 3 types
                        repeat((3 - vm.vehicleTypes.size).coerceAtLeast(0)) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Pickup address ────────────────────────────────────────────────
            SectionLabel("Lokasi Penjemputan")
            OutlinedTextField(
                value = vm.pickupAddress,
                onValueChange = {
                    vm.pickupAddress = it
                    validationError  = null
                },
                placeholder = { Text("Cth: Depan Kantor Camat Melonguane") },
                leadingIcon = {
                    Icon(Icons.Default.LocationOn, null, tint = PrimaryBlue)
                },
                singleLine = false,
                maxLines = 2,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction      = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                supportingText = if (vm.pickupLat == 0.0) {
                    { Text("GPS belum terdeteksi — koordinat diambil otomatis saat tersedia",
                        style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9800)) }
                } else null,
            )

            Spacer(Modifier.height(14.dp))

            // ── Dropoff (transport) / Note (errand) ───────────────────────────
            if (vm.tripType == "transport") {
                SectionLabel("Lokasi Tujuan")
                OutlinedTextField(
                    value = vm.dropoffAddress,
                    onValueChange = {
                        vm.dropoffAddress = it
                        validationError   = null
                    },
                    placeholder = { Text("Cth: Pasar Lama Beo") },
                    leadingIcon = {
                        Icon(Icons.Default.LocationOn, null, tint = Color(0xFFE53935))
                    },
                    singleLine = false,
                    maxLines   = 2,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction      = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                )
            } else {
                SectionLabel("Keterangan Keperluan")
                OutlinedTextField(
                    value = vm.note,
                    onValueChange = {
                        vm.note         = it
                        validationError = null
                    },
                    placeholder = { Text("Cth: Beli 1 kg gula di toko Pak Rudi, kembalikan kembaliannya") },
                    singleLine  = false,
                    minLines    = 3,
                    maxLines    = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                )
            }

            // Validation error
            if (validationError != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    validationError!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(16.dp))
        }

        // ── Bottom button ─────────────────────────────────────────────────────
        Surface(shadowElevation = 8.dp) {
            Button(
                onClick = {
                    val err = vm.validatePicker()
                    if (err != null) { validationError = err; return@Button }
                    onVehicleSelected()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .height(52.dp),
                shape  = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
            ) {
                Text("Lanjut", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── Composable helpers ────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge.copy(
            color = Color(0xFF444444), fontWeight = FontWeight.SemiBold,
        ),
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun TripTypeChip(
    label: String,
    subLabel: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(
            containerColor = if (selected) SelectedBg else UnselectedBg,
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) SelectedBorder else Color(0xFFDDDDDD),
        ),
        elevation = CardDefaults.cardElevation(if (selected) 2.dp else 0.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) PrimaryBlue else Color(0xFF888888),
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) PrimaryBlue else Color(0xFF444444),
                ),
            )
            Text(
                subLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = Color(0xFF888888),
                ),
            )
        }
    }
}

@Composable
private fun VehicleCard(
    vehicleType: VehicleTypeResponse,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val icon = vehicleIcon(vehicleType.code)
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(
            containerColor = if (selected) SelectedBg else UnselectedBg,
        ),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) SelectedBorder else Color(0xFFDDDDDD),
        ),
        elevation = CardDefaults.cardElevation(if (selected) 2.dp else 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                icon,
                contentDescription = vehicleType.name,
                tint = if (selected) PrimaryBlue else Color(0xFF777777),
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                vehicleType.name,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) PrimaryBlue else Color(0xFF444444),
                ),
            )
        }
    }
}

private fun vehicleIcon(code: String): ImageVector = when {
    code.contains("motor", ignoreCase = true) ||
            code.contains("bike",  ignoreCase = true)   -> Icons.Default.TwoWheeler
    code.contains("bentor",  ignoreCase = true) ||
            code.contains("tricycle",ignoreCase = true) ||
            code.contains("rickshaw",ignoreCase = true) -> Icons.Default.ElectricRickshaw
    else                                        -> Icons.Default.DirectionsCar
}