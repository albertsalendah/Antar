package com.richard_salendah.antar.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ElectricRickshaw
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.richard_salendah.antar.data.model.NearbyDriverResponse
import com.richard_salendah.antar.data.model.VehicleTypeResponse
import com.richard_salendah.antar.ui.common.HapticType
import com.richard_salendah.antar.ui.common.OfflineBanner
import com.richard_salendah.antar.ui.common.rememberHaptic
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker

private val TALAUD_FALLBACK = GeoPoint(4.2566, 126.7506)
private val PrimaryBlue     = Color(0xFF1B6CA8)
private val Red             = Color(0xFFE53935)
private val Green           = Color(0xFF2E7D32)

@Composable
fun HomeScreen(
    onStartSearching: (tripId: String) -> Unit,
    onOpenHistory: () -> Unit,
    onOpenProfile: () -> Unit,
    onActiveTripFound: (tripId: String, status: String) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val context       = LocalContext.current
    val lifecycle     = LocalLifecycleOwner.current.lifecycle
    val haptic        = rememberHaptic()
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp

    // Maximum sheet height = 75% of screen
    val maxSheetHeight: Dp = screenHeightDp * 0.75f
    // Minimum (collapsed) sheet height
    val minSheetHeight: Dp = 405.dp

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    var mapView by remember { mutableStateOf<MapView?>(null) }

    // Current sheet height driven by drag + expansion state
    val sheetHeight = minSheetHeight + (maxSheetHeight - minSheetHeight) * viewModel.sheetExpansion

    // ── Startup ───────────────────────────────────────────────────────────────
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
        if (!hasPermission) permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        viewModel.checkActiveTrip(onActiveTripFound)
    }

    // Seed from last known location immediately
    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        try {
            LocationServices.getFusedLocationProviderClient(context)
                .lastLocation
                .addOnSuccessListener { loc ->
                    loc?.let { viewModel.onLocationAvailable(it.latitude, it.longitude) }
                }
        } catch (_: SecurityException) {}
    }

    // Continuous GPS
    DisposableEffect(hasPermission) {
        if (!hasPermission) return@DisposableEffect onDispose {}
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val request     = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L).build()
        val callback    = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { viewModel.onLocationAvailable(it.latitude, it.longitude) }
            }
        }
        try { fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper()) }
        catch (_: SecurityException) {}
        onDispose { fusedClient.removeLocationUpdates(callback) }
    }

    // Centre on first GPS fix
    LaunchedEffect(viewModel.hasInitialCenter) {
        if (!viewModel.hasInitialCenter) return@LaunchedEffect
        val (lat, lng) = viewModel.userLocation ?: return@LaunchedEffect
        mapView?.controller?.animateTo(GeoPoint(lat, lng))
    }

    // Refresh markers on every poll / pin change
    LaunchedEffect(
        viewModel.nearbyDrivers,
        viewModel.userLocation,
        viewModel.pickupLat, viewModel.pickupLng,
        viewModel.dropoffLat, viewModel.dropoffLng,
        viewModel.pickerMode,
    ) {
        val map = mapView ?: return@LaunchedEffect
        updateMarkers(
            map          = map,
            drivers      = viewModel.nearbyDrivers,
            userLocation = viewModel.userLocation,
            pickupLat    = viewModel.pickupLat,
            pickupLng    = viewModel.pickupLng,
            dropoffLat   = viewModel.dropoffLat,
            dropoffLng   = viewModel.dropoffLng,
            pickerMode   = viewModel.pickerMode,
        )
    }

    // Map tap handler
    LaunchedEffect(mapView, viewModel.pickerMode) {
        val map = mapView ?: return@LaunchedEffect
        map.overlays.removeAll { it is MapEventsOverlay }
        if (viewModel.pickerMode != PickerMode.None) {
            val receiver = object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    haptic.perform(HapticType.Tick)
                    viewModel.onMapTapped(p.latitude, p.longitude)
                    viewModel.pickerMode = PickerMode.None
                    return true
                }
                override fun longPressHelper(p: GeoPoint) = false
            }
            map.overlays.add(0, MapEventsOverlay(receiver))
        }
        map.invalidate()
    }

    // MapView lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                Lifecycle.Event.ON_PAUSE  -> mapView?.onPause()
                else                      -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Box(modifier = Modifier.fillMaxSize()) {

        // ── Full-screen map ───────────────────────────────────────────────────
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(15.0)
                    controller.setCenter(TALAUD_FALLBACK)
                    isHorizontalMapRepetitionEnabled = false
                    isVerticalMapRepetitionEnabled   = false
                }.also { mapView = it }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ── Pin mode banner ───────────────────────────────────────────────────
        AnimatedVisibility(
            visible  = viewModel.pickerMode != PickerMode.None,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 60.dp, start = 16.dp, end = 16.dp),
        ) {
            Card(
                shape  = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (viewModel.pickerMode == PickerMode.Pickup)
                        PrimaryBlue else Red,
                ),
                elevation = CardDefaults.cardElevation(6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Default.LocationOn, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text(
                        if (viewModel.pickerMode == PickerMode.Pickup)
                            "Tap peta untuk lokasi penjemputan"
                        else "Tap peta untuk lokasi tujuan",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = Color.White, fontWeight = FontWeight.SemiBold,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Default.Close, "Batal",
                        tint     = Color.White,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable { viewModel.cancelPickerMode() },
                    )
                }
            }
        }

        // ── Top bar ───────────────────────────────────────────────────────────
        Column(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
            OfflineBanner(modifier = Modifier.statusBarsPadding())
            if (!hasPermission) {
                Card(
                    modifier  = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors    = CardDefaults.cardColors(containerColor = Color(0xFFE53935)),
                    shape     = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(6.dp),
                ) {
                    Row(modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.LocationOff, null, tint = Color.White)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Izin lokasi diperlukan", color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium)
                            Text("Aktifkan untuk melihat driver terdekat",
                                color = Color.White.copy(alpha = 0.85f),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                            Text("Izinkan", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                Surface(shape = RoundedCornerShape(24.dp), color = Color.White, shadowElevation = 4.dp) {
                    Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.DirectionsCar, null, tint = PrimaryBlue, modifier = Modifier.size(18.dp))
                        Text(
                            if (viewModel.riderName.isNotEmpty())
                                "Halo, ${viewModel.riderName.split(" ").first()}!"
                            else "Antar",
                            style = MaterialTheme.typography.labelLarge.copy(
                                color = PrimaryBlue, fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapIconButton(Icons.Default.History, "Riwayat", onOpenHistory)
                    MapIconButton(Icons.Default.Person,  "Profil",  onOpenProfile)
                }
            }
        }

        // ── Recenter FAB ──────────────────────────────────────────────────────
        AnimatedVisibility(
            visible  = viewModel.userLocation != null && viewModel.pickerMode == PickerMode.None,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(bottom = sheetHeight-40.dp, end = 16.dp),
        ) {
            FloatingActionButton(
                onClick = {
                    val loc = viewModel.userLocation ?: return@FloatingActionButton
                    mapView?.controller?.animateTo(GeoPoint(loc.first, loc.second))
                },
                shape          = CircleShape,
                containerColor = Color.White,
                contentColor   = PrimaryBlue,
                elevation      = FloatingActionButtonDefaults.elevation(4.dp),
                modifier       = Modifier.size(44.dp),
            ) {
                Icon(Icons.Default.MyLocation, "Ke lokasi saya", modifier = Modifier.size(22.dp))
            }
        }

        // ── Draggable bottom sheet ────────────────────────────────────────────
        var dragAccum by remember { mutableFloatStateOf(0f) }
        val density    = LocalDensity.current

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color.White),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // Drag handle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart  = { dragAccum = 0f },
                                onDragEnd    = {
                                    // Snap to expanded (>0.4) or collapsed
                                    viewModel.sheetExpansion =
                                        if (viewModel.sheetExpansion > 0.4f) 1f else 0f
                                },
                                onVerticalDrag = { _, delta ->
                                    dragAccum += delta
                                    val maxPx = with(density) { (maxSheetHeight - minSheetHeight).toPx() }
                                    viewModel.sheetExpansion =
                                        (viewModel.sheetExpansion - dragAccum / maxPx)
                                            .coerceIn(0f, 1f)
                                    dragAccum = 0f
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFDDDDDD)),
                    )
                }

                when (viewModel.sheetStep) {
                    HomeViewModel.SheetStep.Main    -> MainSheetContent(viewModel, haptic)
                    HomeViewModel.SheetStep.Summary -> SummarySheetContent(
                        viewModel   = viewModel,
                        haptic      = haptic,
                        onConfirmed = { tripId -> onStartSearching(tripId) },
                    )
                }
            }
        }
    }
}

// ── Main sheet content ────────────────────────────────────────────────────────

@Composable
private fun MainSheetContent(
    viewModel: HomeViewModel,
    haptic: com.richard_salendah.antar.ui.common.HapticFeedback,
) {
    var validationError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {

        // ── Trip type toggle ──────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TripTypeChip(
                label    = "Antar",
                icon     = Icons.Default.DirectionsCar,
                selected = viewModel.tripType == "transport",
                modifier = Modifier.weight(1f),
                onClick  = { viewModel.tripType = "transport"; validationError = null },
            )
            TripTypeChip(
                label    = "Errand",
                icon     = Icons.Default.ShoppingCart,
                selected = viewModel.tripType == "errand",
                modifier = Modifier.weight(1f),
                onClick  = { viewModel.tripType = "errand"; validationError = null },
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Vehicle chips with per-type count ─────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            viewModel.vehicleTypes.forEach { vt ->
                val count = viewModel.countByType[vt.name] ?: 0
                VehicleChip(
                    vehicleType = vt,
                    onlineCount = count,
                    selected    = viewModel.selectedType?.id == vt.id,
                    modifier    = Modifier.weight(1f),
                    onClick     = { viewModel.selectedType = vt; validationError = null },
                )
            }
            // Fill empty space if < 3 types
            repeat((3 - viewModel.vehicleTypes.size).coerceAtLeast(0)) {
                Spacer(Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Pickup row ────────────────────────────────────────────────────────
        LocationRow(
            label         = "Penjemputan",
            address       = viewModel.pickupAddress,
            onAddressChange = { viewModel.pickupAddress = it; validationError = null },
            dotColor      = PrimaryBlue,
            isActive      = viewModel.pickerMode == PickerMode.Pickup,
            onPinTap      = {
                haptic.perform(HapticType.Tick)
                viewModel.pickerMode = PickerMode.Pickup
            },
        )

        Spacer(Modifier.height(8.dp))

        // ── Dropoff (transport) / Note (errand) ───────────────────────────────
        if (viewModel.tripType == "transport") {
            LocationRow(
                label           = "Tujuan",
                address         = viewModel.dropoffAddress,
                onAddressChange = { viewModel.dropoffAddress = it; validationError = null },
                dotColor        = Red,
                isActive        = viewModel.pickerMode == PickerMode.Dropoff,
                onPinTap        = {
                    haptic.perform(HapticType.Tick)
                    viewModel.pickerMode = PickerMode.Dropoff
                },
                onAddressConfirmed = { addr ->
                    viewModel.geocodeDropoff(addr) { _, _ -> }
                },
            )
        } else {
            OutlinedTextField(
                value           = viewModel.note,
                onValueChange   = { viewModel.note = it; validationError = null },
                label           = { Text("Keterangan keperluan") },
                placeholder     = { Text("Cth: Beli 1 kg gula di toko Pak Rudi") },
                singleLine      = false,
                minLines        = 2,
                maxLines        = 3,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                modifier = Modifier.fillMaxWidth(),
                shape    = RoundedCornerShape(10.dp),
            )
        }

        if (validationError != null) {
            Spacer(Modifier.height(4.dp))
            Text(validationError!!, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))

        // ── Lanjut button ─────────────────────────────────────────────────────
        Button(
            onClick = {
                val err = viewModel.validate()
                if (err != null) { validationError = err; return@Button }
                haptic.perform(HapticType.Tick)
                viewModel.sheetStep      = HomeViewModel.SheetStep.Summary
                viewModel.sheetExpansion = 1f
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape    = RoundedCornerShape(12.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
        ) {
            Text("Lanjut", fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Summary sheet content ─────────────────────────────────────────────────────

@Composable
private fun SummarySheetContent(
    viewModel: HomeViewModel,
    haptic: com.richard_salendah.antar.ui.common.HapticFeedback,
    onConfirmed: (tripId: String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Default.Close, "Kembali",
                tint     = Color(0xFF888888),
                modifier = Modifier
                    .size(20.dp)
                    .clickable {
                        viewModel.sheetStep      = HomeViewModel.SheetStep.Main
                        viewModel.sheetExpansion = 0f
                    },
            )
            Text("Ringkasan Pesanan",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        }

        Spacer(Modifier.height(12.dp))

        // Type + vehicle badge
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFE8F4FD)) {
                Text(
                    if (viewModel.tripType == "transport") "Antar" else "Errand",
                    style    = MaterialTheme.typography.labelMedium.copy(
                        color = PrimaryBlue, fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            viewModel.selectedType?.let { vt ->
                Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFF5F5F5)) {
                    Row(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(vehicleIcon(vt.code), null, tint = Color(0xFF555555), modifier = Modifier.size(14.dp))
                        Text(vt.name, style = MaterialTheme.typography.labelMedium.copy(color = Color(0xFF444444)))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        HorizontalDivider(color = Color(0xFFF0F0F0))
        Spacer(Modifier.height(10.dp))

        SummaryDetailRow(Icons.Default.LocationOn, PrimaryBlue, "Penjemputan",
            viewModel.pickupAddress.ifBlank { "%.5f, %.5f".format(viewModel.pickupLat, viewModel.pickupLng) })

        if (viewModel.tripType == "transport") {
            Spacer(Modifier.height(10.dp))
            SummaryDetailRow(Icons.Default.LocationOn, Red, "Tujuan",
                viewModel.dropoffAddress.ifBlank { "%.5f, %.5f".format(viewModel.dropoffLat, viewModel.dropoffLng) })
        } else {
            Spacer(Modifier.height(10.dp))
            SummaryDetailRow(Icons.Default.ShoppingCart, Color(0xFF777777), "Keterangan", viewModel.note)
        }

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = Color(0xFFF0F0F0))
        Spacer(Modifier.height(10.dp))

        SummaryDetailRow(Icons.Default.Check, Green, "Pembayaran", "Tunai")

        // Info banner
        Spacer(Modifier.height(12.dp))
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
            elevation = CardDefaults.cardElevation(0.dp)) {
            Text(
                "Harga ditentukan driver. Anda dapat menerima, menolak, atau menawar.",
                style    = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF5D4037)),
                modifier = Modifier.padding(12.dp),
            )
        }

        if (viewModel.bookingError != null) {
            Spacer(Modifier.height(8.dp))
            Text(viewModel.bookingError!!, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                viewModel.requestRide(
                    onSuccess = { tripId ->
                        haptic.perform(HapticType.Confirm)
                        onConfirmed(tripId)
                    },
                    onError = { err ->
                        haptic.perform(HapticType.Error)
                        viewModel.bookingError = err
                    },
                )
            },
            enabled  = !viewModel.bookingLoading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape    = RoundedCornerShape(12.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
        ) {
            if (viewModel.bookingLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            } else {
                Text("Pesan Sekarang", fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Map marker helpers ────────────────────────────────────────────────────────

private fun updateMarkers(
    map: MapView,
    drivers: List<NearbyDriverResponse>,
    userLocation: Pair<Double, Double>?,
    pickupLat: Double, pickupLng: Double,
    dropoffLat: Double, dropoffLng: Double,
    pickerMode: PickerMode,
) {
    map.overlays.removeAll { it is Marker }

    // Driver pins
    drivers.forEach { driver ->
        Marker(map).apply {
            id = "driver_${driver.driverId}"; position = GeoPoint(driver.lat, driver.lng)
            title = driver.fullName; snippet = driver.vehicleType
            icon = circleDrawable(map.context, 0xFF03A9F4.toInt(), 24)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); map.overlays.add(this)
        }
    }
    // User location
//    userLocation?.let { (lat, lng) ->
//        Marker(map).apply {
//            id = "user"; position = GeoPoint(lat, lng); title = "Lokasi Anda"
//            icon = circleDrawable(map.context, 0xFF1B6CA8.toInt(), 18)
//            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); map.overlays.add(this)
//        }
//    }
    // Pickup pin
    if (pickupLat != 0.0 && pickerMode != PickerMode.Pickup) {
        Marker(map).apply {
            id = "pickup"; position = GeoPoint(pickupLat, pickupLng); title = "Penjemputan"
            icon = pinDrawable(map.context, 0xFF1B6CA8.toInt(), 30)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); map.overlays.add(this)
        }
    }
    // Dropoff pin
    if (dropoffLat != 0.0 && pickerMode != PickerMode.Dropoff) {
        Marker(map).apply {
            id = "dropoff"; position = GeoPoint(dropoffLat, dropoffLng); title = "Tujuan"
            icon = pinDrawable(map.context, 0xFFE53935.toInt(), 30)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); map.overlays.add(this)
        }
    }
    map.invalidate()
}

private fun circleDrawable(context: android.content.Context, colorInt: Int, sizeDp: Int)
        : android.graphics.drawable.BitmapDrawable {
    val px  = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(8)
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val cvs = Canvas(bmp)
    cvs.drawCircle(px / 2f, px / 2f, px / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt })
    cvs.drawCircle(px / 2f, px / 2f, px / 2f - px * 0.09f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = px * 0.18f
        })
    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

private fun pinDrawable(
    context:  android.content.Context,
    colorInt: Int,
    sizeDp:   Int,
): android.graphics.drawable.BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val w = (sizeDp * density).coerceAtLeast(8f)
    val h = w * 1.35f
    val cx = w / 2f
    val r  = w / 2f
    val bmp    = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val fill   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt; style = Paint.Style.FILL }
    canvas.drawCircle(cx, r, r, fill)
    val tail = android.graphics.Path().apply {
        moveTo(cx - r * 0.78f, r + r * 0.55f)
        lineTo(cx + r * 0.78f, r + r * 0.55f)
        lineTo(cx, h)
        close()
    }
    canvas.drawPath(tail, fill)
    val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, r, r * 0.42f, holePaint)
    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

// ── Micro-composables ─────────────────────────────────────────────────────────

@Composable
private fun LocationRow(
    label: String,
    address: String,
    onAddressChange: (String) -> Unit,
    dotColor: Color,
    isActive: Boolean,
    onPinTap: () -> Unit,
    onAddressConfirmed: ((String) -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Pin button
        Surface(
            shape  = CircleShape,
            color  = if (isActive) dotColor else dotColor.copy(alpha = 0.12f),
            modifier = Modifier.size(34.dp).clickable(onClick = onPinTap),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(Icons.Default.LocationOn, label,
                    tint     = if (isActive) Color.White else dotColor,
                    modifier = Modifier.size(18.dp))
            }
        }
        OutlinedTextField(
            value           = address,
            onValueChange   = onAddressChange,
            label           = { Text(label) },
            singleLine      = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction      = ImeAction.Done,
            ),
            modifier = Modifier.weight(1f),
            shape    = RoundedCornerShape(10.dp),
        )
    }
}

@Composable
private fun VehicleChip(
    vehicleType: VehicleTypeResponse,
    onlineCount: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier  = modifier.clickable(onClick = onClick),
        shape     = RoundedCornerShape(10.dp),
        colors    = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFFE8F4FD) else Color(0xFFF9F9F9),
        ),
        border    = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) PrimaryBlue else Color(0xFFDDDDDD),
        ),
        elevation = CardDefaults.cardElevation(if (selected) 2.dp else 0.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(vehicleIcon(vehicleType.code), vehicleType.name,
                tint     = if (selected) PrimaryBlue else Color(0xFF777777),
                modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(3.dp))
            Text(vehicleType.name,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    color      = if (selected) PrimaryBlue else Color(0xFF444444)))
            // Online driver count badge
            Surface(shape = RoundedCornerShape(4.dp),
                color = if (onlineCount > 0) Color(0xFFE8F5E9) else Color(0xFFF5F5F5)) {
                Text(
                    if (onlineCount > 0) "$onlineCount online" else "—",
                    style    = MaterialTheme.typography.labelSmall.copy(
                        color = if (onlineCount > 0) Green else Color(0xFFBBBBBB),
                        fontWeight = FontWeight.Medium),
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun TripTypeChip(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape    = RoundedCornerShape(10.dp),
        color    = if (selected) Color(0xFFE8F4FD) else Color(0xFFF5F5F5),
        border   = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) PrimaryBlue else Color(0xFFDDDDDD),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, tint = if (selected) PrimaryBlue else Color(0xFF777777),
                modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelMedium.copy(
                color = if (selected) PrimaryBlue else Color(0xFF555555),
                fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable
private fun SummaryDetailRow(icon: ImageVector, iconTint: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(16.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFAAAAAA)))
            Text(value, style = MaterialTheme.typography.bodySmall.copy(
                color = Color(0xFF1A1A1A), fontWeight = FontWeight.Medium))
        }
    }
}

@Composable
private fun MapIconButton(icon: ImageVector, desc: String, onClick: () -> Unit) {
    Surface(shape = CircleShape, color = Color.White, shadowElevation = 4.dp, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().clickable(onClick = onClick)) {
            Icon(icon, desc, tint = PrimaryBlue, modifier = Modifier.size(20.dp))
        }
    }
}

private fun vehicleIcon(code: String): ImageVector = when {
    code.contains("motor",    ignoreCase = true) ||
            code.contains("bike",     ignoreCase = true)   -> Icons.Default.TwoWheeler
    code.contains("bentor",   ignoreCase = true) ||
            code.contains("rickshaw", ignoreCase = true)   -> Icons.Default.ElectricRickshaw
    else                                           -> Icons.Default.DirectionsCar
}
