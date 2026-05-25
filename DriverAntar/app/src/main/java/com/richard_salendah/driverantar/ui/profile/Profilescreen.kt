package com.richard_salendah.driverantar.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.richard_salendah.driverantar.data.model.VehicleResponse
import com.richard_salendah.driverantar.ui.components.RatingBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onBack: () -> Unit,
    onAddVehicle: () -> Unit,
    onLogout: () -> Unit,
    onOpenEarnings: () -> Unit = {},
    onOpenTripHistory: () -> Unit = {}
) {
    val context = LocalContext.current
    val profile = viewModel.profile
    val vehicles = viewModel.vehicles
    val activeVehicleId = profile?.active_vehicle_id

    var deleteTarget by remember { mutableStateOf<VehicleResponse?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = context.contentResolver.openInputStream(uri)?.readBytes()
            ?: return@rememberLauncherForActivityResult
        viewModel.uploadAvatar(bytes, mime)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onLogout) {
                        Text("Logout", color = MaterialTheme.colorScheme.error)
                    }
                }
            )
        }
    ) { padding ->

        if (viewModel.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── Profile card ──────────────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {

                            // Avatar
                            Box(
                                modifier = Modifier.size(80.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val url = viewModel.avatarUrl
                                if (url != null) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(url).crossfade(true)
                                            .diskCachePolicy(CachePolicy.DISABLED)
                                            .memoryCachePolicy(CachePolicy.DISABLED).build(),
                                        contentDescription = "Profile picture",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(80.dp)
                                            .clip(CircleShape)
                                            .border(
                                                2.dp,
                                                MaterialTheme.colorScheme.primary,
                                                CircleShape
                                            )
                                            .clickable { imagePicker.launch("image/*") }
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(80.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .border(
                                                2.dp,
                                                MaterialTheme.colorScheme.outline,
                                                CircleShape
                                            )
                                            .clickable { imagePicker.launch("image/*") },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (viewModel.isUploadingAvatar)
                                            CircularProgressIndicator(
                                                Modifier.size(28.dp),
                                                strokeWidth = 2.dp
                                            )
                                        else
                                            Icon(
                                                Icons.Filled.CameraAlt, null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(32.dp)
                                            )
                                    }
                                }
                                if (viewModel.isUploadingAvatar && url != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(80.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            Modifier.size(28.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.width(16.dp))

                            // Info
                            Column(modifier = Modifier.weight(1f)) {
                                Text(profile?.full_name?.ifBlank { "—" } ?: "—",
                                    style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(2.dp))
                                Text(profile?.phone_number?.ifBlank { "—" } ?: "—",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (profile?.email?.isNotBlank() == true) {
                                    Text(
                                        profile.email,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.height(6.dp))

                                // Online status
                                Text(
                                    if (profile?.is_online == true) "● Online" else "○ Offline",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (profile?.is_online == true)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))

                                // Rating — shown once driver has received at least one rating
                                val avgRating = profile?.avg_rating
                                if (avgRating != null && profile.rating_count > 0) {
                                    RatingBar(
                                        rating = avgRating,
                                        ratingCount = profile.rating_count,
                                        starSize = 16.dp,
                                        showCount = true
                                    )
                                    Spacer(Modifier.height(4.dp))
                                } else {
                                    // No ratings yet — show a greyed-out placeholder so the space isn't empty
                                    Text(
                                        "No ratings yet",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(4.dp))
                                }

                                // Island badge
                                val islandName = profile?.island_name
                                if (islandName != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(MaterialTheme.colorScheme.secondaryContainer)
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.LocationOn, null,
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            islandName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                } else {
                                    Text(
                                        "Island: go online to detect",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Tap the circle to change your photo",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Shortcuts row: Earnings + Trip History ────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenEarnings,
                        modifier = Modifier.weight(1f)
                    ) { Text("Pendapatan") }

                    OutlinedButton(
                        onClick = onOpenTripHistory,
                        modifier = Modifier.weight(1f)
                    ) { Text("Riwayat Trip") }
                }
            }

            // ── Error banner ──────────────────────────────────────────────────
            viewModel.errorMessage?.let { msg ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                msg,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.clearError() }) { Text("Dismiss") }
                        }
                    }
                }
            }

            // ── Vehicles header ───────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("My Vehicles", style = MaterialTheme.typography.titleMedium)
                    FilledTonalButton(
                        onClick = onAddVehicle,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Vehicle")
                    }
                }
            }

            if (vehicles.isEmpty()) {
                item {
                    Text(
                        "No vehicles yet. Tap \"Add Vehicle\" to register one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                item {
                    Text(
                        "Only one vehicle can be active at a time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(vehicles, key = { it.id }) { vehicle ->
                    VehicleCard(
                        vehicle = vehicle,
                        isActive = vehicle.id == activeVehicleId,
                        isSetting = viewModel.isSettingActive,
                        onSetActive = { viewModel.setActiveVehicle(vehicle.id) },
                        onDelete = { deleteTarget = vehicle }
                    )
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    deleteTarget?.let { v ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Remove vehicle?") },
            text = { Text("${v.make} ${v.model} (${v.license_plate}) will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteVehicle(v.id); deleteTarget = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun VehicleCard(
    vehicle: VehicleResponse, isActive: Boolean, isSetting: Boolean,
    onSetActive: () -> Unit, onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${vehicle.make} ${vehicle.model}".trim().ifBlank { "Vehicle" },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (isActive) {
                        Spacer(Modifier.width(8.dp))
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text(
                                    "Active today",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            })
                    }
                }
                Text(
                    vehicle.vehicle_type, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    buildString {
                        append(vehicle.license_plate)
                        if (vehicle.color.isNotBlank()) append("  •  ${vehicle.color}")
                        if (vehicle.year > 0) append("  •  ${vehicle.year}")
                    }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (!isActive) {
                    FilledTonalButton(
                        onClick = onSetActive, enabled = !isSetting,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        if (isSetting) CircularProgressIndicator(
                            Modifier.size(14.dp),
                            strokeWidth = 1.5.dp
                        )
                        else Text("Use today", style = MaterialTheme.typography.labelMedium)
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Delete, "Delete",
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}