package com.richard_salendah.driverantar.ui.map

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.richard_salendah.driverantar.ui.components.RatingChip
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: MapViewModel,
    onOpenProfile: () -> Unit,
    onAddVehicle: () -> Unit,
    onNavigateToTrip: (route: String) -> Unit = {},
    onOpenIncomingTrips: () -> Unit = {}
) {
    val context = LocalContext.current

    // Active trip recovery — once on first composition
    LaunchedEffect(Unit) { viewModel.recoverActiveTrip() }

    // Refresh profile on every resume — this fires when the driver returns
    // from ProfileScreen (after adding a vehicle / setting one active), from
    // device Settings, or when the app comes back to foreground.
    // Without this, activeVehicleId stays null after navigating back and the
    // switch remains disabled even after the driver picked a vehicle.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshProfile()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val recoveredRoute = viewModel.recoveredTripRoute
    LaunchedEffect(recoveredRoute) {
        if (recoveredRoute != null) {
            viewModel.clearRecoveredRoute()
            onNavigateToTrip(recoveredRoute)
        }
    }

    // Switch is enabled only when ALL conditions are met:
    //   1. Location permission granted
    //   2. GPS turned on
    //   3. Driver has an active vehicle selected
    // Condition 3 is new — prevents new accounts from going online before
    // adding a vehicle. The no-vehicle dialog is shown on tap as a fallback
    // but disabling the switch is a cleaner UX.
    val switchEnabled = viewModel.permissionGranted
            && viewModel.gpsEnabled
            && viewModel.activeVehicleId != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Driver Mode")
                        if (viewModel.driverName.isNotBlank()) {
                            Text(
                                "Hi, ${viewModel.driverName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Rating — visible once the driver has at least one rating
                        val rating = viewModel.avgRating
                        if (rating != null && viewModel.ratingCount > 0) {
                            RatingChip(
                                rating      = rating,
                                ratingCount = viewModel.ratingCount
                            )
                        }
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (viewModel.isOnline) "Online" else "Offline",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.width(4.dp))
                        Switch(
                            checked         = viewModel.isOnline,
                            enabled         = switchEnabled,
                            onCheckedChange = { viewModel.toggleOnline(it, context) }
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    IconButton(onClick = onOpenProfile) {
                        val url = viewModel.avatarUrl
                        if (url != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(url)
                                    .crossfade(true)
                                    .diskCachePolicy(CachePolicy.DISABLED)
                                    .memoryCachePolicy(CachePolicy.DISABLED)
                                    .build(),
                                contentDescription = "Profile",
                                contentScale       = ContentScale.Crop,
                                modifier           = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            )
                        } else {
                            Icon(
                                Icons.Filled.AccountCircle,
                                contentDescription = "Profile",
                                modifier           = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (viewModel.isOnline) {
                ExtendedFloatingActionButton(
                    onClick = onOpenIncomingTrips,
                    text    = { Text("Lihat Perjalanan") },
                    icon    = {}
                )
            }
        }
    ) { padding ->

        val mapViewRef = remember { mutableStateOf<MapView?>(null) }

        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val initialCenterDone = remember { mutableStateOf(false) }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory  = { ctx ->
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(15.0)
                        controller.setCenter(GeoPoint(1.4748, 124.8421))
                        val marker = Marker(this).apply {
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            title = "You"
                        }
                        overlays.add(marker)
                        tag = marker
                        mapViewRef.value = this
                    }
                },
                update = { mapView ->
                    val marker   = mapView.tag as Marker
                    val geoPoint = viewModel.currentGeoPoint
                    if (geoPoint.latitude != 0.0 || geoPoint.longitude != 0.0) {
                        // Always keep the marker on the correct spot
                        marker.position = geoPoint

                        // Center the camera on the first valid GPS fix after
                        // every (re)composition — this covers:
                        //   1. Fresh app start (no position yet)
                        //   2. Navigation back to this screen (AndroidView recreated,
                        //      initialCenterDone resets to false, camera re-centers once)
                        if (!initialCenterDone.value) {
                            mapView.controller.setCenter(geoPoint)
                            mapView.controller.setZoom(15.0)
                            initialCenterDone.value = true
                        }

                        mapView.invalidate()
                        mapViewRef.value = mapView
                    }
                }
            )

            // ── Status banners ────────────────────────────────────────────────
            val showPermissionBanner = !viewModel.permissionGranted
            val showGpsBanner        = viewModel.permissionGranted && !viewModel.gpsEnabled
            val showNoVehicleBanner  = viewModel.permissionGranted
                    && viewModel.gpsEnabled
                    && viewModel.activeVehicleId == null

            if (showPermissionBanner || showGpsBanner || showNoVehicleBanner) {
                Card(
                    colors   = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        when {
                            showPermissionBanner -> {
                                Text(
                                    "Location permission required",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Grant location permission to go online.\n" +
                                            "Settings → Apps → Antar Driver → Permissions → Location.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            showGpsBanner -> {
                                Text(
                                    "GPS is turned off",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Turn on Location / GPS to go online.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.height(8.dp))
                                FilledTonalButton(onClick = {
                                    context.startActivity(
                                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                                    )
                                }) { Text("Open Location Settings") }
                            }
                            showNoVehicleBanner -> {
                                Text(
                                    "No vehicle selected",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Add a vehicle and tap \"Use today\" in your profile to go online.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.height(8.dp))
                                FilledTonalButton(onClick = onAddVehicle) {
                                    Text("Go to Profile")
                                }
                            }
                        }
                    }
                }
            }

            // ── Recenter button ───────────────────────────────────────────────
            val hasPosition = viewModel.currentGeoPoint.let {
                it.latitude != 0.0 || it.longitude != 0.0
            }
            if (hasPosition) {
                SmallFloatingActionButton(
                    onClick = {
                        mapViewRef.value?.controller?.animateTo(viewModel.currentGeoPoint)
                    },
                    modifier       = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end    = 16.dp,
                            bottom = if (viewModel.isOnline) 80.dp else 16.dp
                        ),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor   = MaterialTheme.colorScheme.primary
                ) {
                    Icon(
                        Icons.Filled.MyLocation,
                        contentDescription = "Kembali ke posisi saya"
                    )
                }
            }
        }
    }

    // No vehicle dialog (tapped switch when no vehicle — defence-in-depth)
    if (viewModel.showNoVehicleDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissNoVehicleDialog() },
            title = { Text("No Vehicle Selected") },
            text  = {
                Text(
                    "Please go to your profile, add a vehicle and tap \"Use today\" " +
                            "before going online."
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.dismissNoVehicleDialog()
                    onAddVehicle()
                }) { Text("Go to Profile") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissNoVehicleDialog() }) {
                    Text("Later")
                }
            }
        )
    }
}