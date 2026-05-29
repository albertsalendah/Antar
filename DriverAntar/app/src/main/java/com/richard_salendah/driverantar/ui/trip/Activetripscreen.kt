package com.richard_salendah.driverantar.ui.trip

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.richard_salendah.driverantar.data.model.DriverTripResponse
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveTripScreen(
    viewModel: ActiveTripViewModel,
    onTripCompleted: (tripId: String) -> Unit,
    onTripCancelled: () -> Unit
) {
    val context        = LocalContext.current
    val lifecycle      = LocalLifecycleOwner.current.lifecycle
    val haptic         = LocalHapticFeedback.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val density        = LocalDensity.current

    val maxSheetHeight: Dp = screenHeightDp * 0.75f
    val minSheetHeight: Dp = 200.dp

    val uiState        = viewModel.uiState
    val trip           = viewModel.trip
    val driverGeoPoint = viewModel.currentGeoPoint
    val routePoints    = viewModel.routePoints

    var mapView        by remember { mutableStateOf<MapView?>(null) }
    var sheetExpansion by remember { mutableFloatStateOf(0f) }
    var dragAccum      by remember { mutableFloatStateOf(0f) }
    val sheetHeight    = minSheetHeight + (maxSheetHeight - minSheetHeight) * sheetExpansion

    // ── Navigation side-effects ───────────────────────────────────────────────
    LaunchedEffect(uiState) {
        when (uiState) {
            is ActiveTripUiState.Completed -> onTripCompleted(viewModel.tripId)
            is ActiveTripUiState.Cancelled -> onTripCancelled()
            else -> Unit
        }
    }

    // ── Centre map on driver position (first fix only) ────────────────────────
    var initialCentered by remember { mutableStateOf(false) }
    LaunchedEffect(driverGeoPoint) {
        val map = mapView ?: return@LaunchedEffect
        if (!initialCentered && (driverGeoPoint.latitude != 0.0 || driverGeoPoint.longitude != 0.0)) {
            map.controller.setCenter(driverGeoPoint)
            map.controller.setZoom(16.0)
            initialCentered = true
        }
    }

    // ── Refresh map overlays ──────────────────────────────────────────────────
    LaunchedEffect(driverGeoPoint, trip, routePoints) {
        val map = mapView ?: return@LaunchedEffect
        updateOverlays(map, trip, driverGeoPoint, routePoints)
    }

    // ── MapView lifecycle ─────────────────────────────────────────────────────
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                Lifecycle.Event.ON_PAUSE  -> mapView?.onPause()
                else -> Unit
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
                    controller.setZoom(16.0)
                    isHorizontalMapRepetitionEnabled = false
                    isVerticalMapRepetitionEnabled   = false
                }.also { mapView = it }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ── Recenter FAB ──────────────────────────────────────────────────────
        if (driverGeoPoint.latitude != 0.0) {
            SmallFloatingActionButton(
                onClick = { mapView?.controller?.animateTo(driverGeoPoint) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = sheetHeight + 12.dp),
                containerColor = Color.White,
                contentColor   = Color(0xFF1B6CA8),
            ) {
                Icon(Icons.Default.MyLocation, "Ke posisi saya", modifier = Modifier.size(20.dp))
            }
        }

        // ── Draggable bottom sheet ────────────────────────────────────────────
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
                                onDragStart = { dragAccum = 0f },
                                onDragEnd   = {
                                    sheetExpansion = if (sheetExpansion > 0.4f) 1f else 0f
                                },
                                onVerticalDrag = { _, delta ->
                                    dragAccum += delta
                                    val maxPx = with(density) {
                                        (maxSheetHeight - minSheetHeight).toPx()
                                    }
                                    sheetExpansion =
                                        (sheetExpansion - dragAccum / maxPx).coerceIn(0f, 1f)
                                    dragAccum = 0f
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(40.dp).height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFDDDDDD)),
                    )
                }

                when {
                    // ── Loading ───────────────────────────────────────────────
                    uiState is ActiveTripUiState.Loading || trip == null -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(color = Color(0xFF1B6CA8)) }
                    }

                    // ── Error ─────────────────────────────────────────────────
                    uiState is ActiveTripUiState.Error -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                uiState.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { viewModel.clearError() }) { Text("OK") }
                        }
                    }

                    // ── Active content ────────────────────────────────────────
                    else -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // Status stepper — always visible
                            TripStatusStepper(status = trip.status)

                            // Rider info card — always visible
                            RiderInfoCard(
                                trip    = trip,
                                onCall  = {
                                    val phone  = trip.rider_phone.ifEmpty { null }
                                    val intent = if (phone != null)
                                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                                    else Intent(Intent.ACTION_DIAL)
                                    context.startActivity(intent)
                                },
                            )

                            // Expanded: fare + address detail
                            if (sheetExpansion > 0.1f) {
                                FareCard(trip = trip)
                                AddressCard(trip = trip)
                            }

                            // Action buttons — always visible
                            val isLoading = uiState is ActiveTripUiState.ActionLoading
                            when (trip.status) {
                                "agreed" -> {
                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.startTrip()
                                        },
                                        enabled  = !isLoading,
                                        modifier = Modifier.fillMaxWidth().height(52.dp),
                                    ) {
                                        if (isLoading) CircularProgressIndicator(
                                            Modifier.size(20.dp), strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                        else Text(
                                            "Mulai Perjalanan",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                    }
                                    OutlinedButton(
                                        onClick  = { viewModel.requestCancel() },
                                        enabled  = !isLoading,
                                        modifier = Modifier.fillMaxWidth().height(48.dp),
                                        colors   = ButtonDefaults.outlinedButtonColors(
                                            contentColor = MaterialTheme.colorScheme.error,
                                        ),
                                    ) { Text("Batalkan Trip") }
                                }

                                "in_progress" -> {
                                    Button(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.completeTrip()
                                        },
                                        enabled  = !isLoading,
                                        modifier = Modifier.fillMaxWidth().height(52.dp),
                                        colors   = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.tertiary,
                                        ),
                                    ) {
                                        if (isLoading) CircularProgressIndicator(
                                            Modifier.size(20.dp), strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onTertiary,
                                        )
                                        else Text(
                                            "Selesaikan Perjalanan",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onTertiary,
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    // ── Cancel confirmation dialog ────────────────────────────────────────────
    if (uiState is ActiveTripUiState.Confirming) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCancel() },
            title = { Text("Batalkan Trip?") },
            text  = {
                Text(
                    "Trip akan dikembalikan ke antrean dan penumpang akan " +
                            "mencari driver lain. Yakin ingin membatalkan?"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmCancel() }) {
                    Text("Ya, Batalkan", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCancel() }) { Text("Tidak") }
            },
        )
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun TripStatusStepper(status: String) {
    val steps = listOf(
        Triple("agreed",      "Menuju Penumpang",   Icons.Default.Person),
        Triple("in_progress", "Dalam Perjalanan",   Icons.Default.DirectionsCar),
        Triple("completed",   "Sampai Tujuan",      Icons.Default.LocationOn),
    )
    val currentIndex = steps.indexOfFirst { it.first == status }.coerceAtLeast(0)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        steps.forEachIndexed { index, (_, label, icon) ->
            val isDone   = index <= currentIndex
            val isActive = index == currentIndex
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier            = Modifier.weight(1f),
            ) {
                Surface(
                    shape    = CircleShape,
                    color    = if (isDone) Color(0xFF1B6CA8) else Color(0xFFEEEEEE),
                    modifier = Modifier.size(if (isActive) 40.dp else 32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        if (isActive && status != "completed") {
                            CircularProgressIndicator(
                                color       = Color.White,
                                modifier    = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                icon, null,
                                tint     = if (isDone) Color.White else Color(0xFFBBBBBB),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color      = if (isDone) Color(0xFF1B6CA8) else Color(0xFFAAAAAA),
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    ),
                )
            }
            if (index < steps.lastIndex) {
                Box(
                    modifier = Modifier
                        .weight(0.3f).height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            if (index < currentIndex) Color(0xFF1B6CA8) else Color(0xFFEEEEEE)
                        ),
                )
            }
        }
    }
}

@Composable
private fun RiderInfoCard(trip: DriverTripResponse, onCall: () -> Unit) {
    Surface(
        modifier        = Modifier.fillMaxWidth(),
        shape           = RoundedCornerShape(12.dp),
        color           = Color(0xFFF8F8F8),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = CircleShape, color = Color(0xFFE8F4FD), modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.Person, null, tint = Color(0xFF1B6CA8), modifier = Modifier.size(24.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Penumpang",
                    style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF999999)),
                )
                Text(
                    trip.rider_name.ifEmpty { "—" },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold, color = Color(0xFF1A1A1A),
                    ),
                )
                if (trip.rider_phone.isNotBlank()) {
                    Text(
                        trip.rider_phone,
                        style = MaterialTheme.typography.bodySmall.copy(color = Color(0xFF888888)),
                    )
                }
            }
            FilledIconButton(
                onClick  = onCall,
                colors   = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color(0xFF1B6CA8),
                ),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.Call, "Hubungi penumpang", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun FareCard(trip: DriverTripResponse) {
    Surface(
        modifier        = Modifier.fillMaxWidth(),
        shape           = RoundedCornerShape(12.dp),
        color           = Color(0xFFF8F8F8),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "Harga Disepakati",
                    style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF999999)),
                )
                Text(
                    formatRupiah(trip.fare ?: trip.offered_fare ?: 0.0),
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = Color(0xFF1B6CA8), fontWeight = FontWeight.Bold,
                    ),
                )
            }
            Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFFE8F4FD)) {
                Text(
                    trip.payment_method.replaceFirstChar { it.uppercase() },
                    style    = MaterialTheme.typography.labelMedium.copy(
                        color = Color(0xFF1B6CA8), fontWeight = FontWeight.SemiBold,
                    ),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun AddressCard(trip: DriverTripResponse) {
    Surface(
        modifier        = Modifier.fillMaxWidth(),
        shape           = RoundedCornerShape(12.dp),
        color           = Color(0xFFF8F8F8),
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Errand note
            if (!trip.note.isNullOrBlank()) {
                Card(
                    colors   = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            "Instruksi",
                            style      = MaterialTheme.typography.labelSmall,
                            color      = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            trip.note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }

            AddressRow(Icons.Default.LocationOn, Color(0xFF1B6CA8), "Jemput", trip.pickup_address)

            if (!trip.dropoff_address.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFFF0F0F0))
                Spacer(Modifier.height(8.dp))
                AddressRow(Icons.Default.LocationOn, Color(0xFFE53935), "Tujuan", trip.dropoff_address!!)
            } else if (trip.trip_type == "errand") {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tujuan: belum ditentukan",
                    style     = MaterialTheme.typography.bodySmall,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
    }
}

@Composable
private fun AddressRow(icon: ImageVector, iconTint: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(16.dp).padding(top = 2.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFFAAAAAA))
            Spacer(Modifier.height(2.dp))
            Text(value, style = MaterialTheme.typography.bodyMedium.copy(
                color = Color(0xFF1A1A1A), fontWeight = FontWeight.Medium,
            ))
        }
    }
}

// ── Map overlay helpers ───────────────────────────────────────────────────────

private fun updateOverlays(
    map:         MapView,
    trip:        DriverTripResponse?,
    driverPoint: GeoPoint,
    routePoints: List<GeoPoint>,
) {
    map.overlays.removeAll { it is Marker || it is Polyline }

    // Route line
    if (routePoints.isNotEmpty()) {
        val lineColor = if (trip?.status == "in_progress")
            android.graphics.Color.parseColor("#E53935")
        else
            android.graphics.Color.parseColor("#1B6CA8")
        Polyline(map).apply {
            setPoints(routePoints)
            outlinePaint.color       = lineColor
            outlinePaint.strokeWidth = 8f
            outlinePaint.isAntiAlias = true
            outlinePaint.strokeCap   = android.graphics.Paint.Cap.ROUND
            outlinePaint.strokeJoin  = android.graphics.Paint.Join.ROUND
            map.overlays.add(this)
        }
    }

    trip?.let { t ->
        // Pickup pin — always visible
        if (t.pickup_lat != 0.0) {
            Marker(map).apply {
                position = GeoPoint(t.pickup_lat, t.pickup_lng)
                title    = "Jemput: ${t.rider_name}"
                snippet  = t.pickup_address
                icon     = circleDrawable(map.context, 0xFF1B6CA8.toInt(), 30)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(this)
            }
        }
        // Dropoff pin — transport + in_progress only
        if (t.status == "in_progress" && t.trip_type == "transport" && t.dropoff_lat != 0.0) {
            Marker(map).apply {
                position = GeoPoint(t.dropoff_lat ?: 0.0, t.dropoff_lng?:0.0)
                title    = "Tujuan"
                snippet  = t.dropoff_address ?: ""
                icon     = circleDrawable(map.context, 0xFFE53935.toInt(), 30)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(this)
            }
        }
    }

    // Driver pin — moving
    if (driverPoint.latitude != 0.0) {
        Marker(map).apply {
            position = driverPoint
            title    = "Posisi Anda"
            icon     = circleDrawable(map.context, 0xFF03A9F4.toInt(), 32)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            map.overlays.add(this)
        }
    }

    map.invalidate()
}

private fun circleDrawable(
    context:  android.content.Context,
    colorInt: Int,
    sizeDp:   Int,
): android.graphics.drawable.BitmapDrawable {
    val px  = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(8)
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val cvs = Canvas(bmp)
    cvs.drawCircle(px / 2f, px / 2f, px / 2f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt })
    cvs.drawCircle(px / 2f, px / 2f, px / 2f - px * 0.09f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color       = android.graphics.Color.WHITE
            style       = Paint.Style.STROKE
            strokeWidth = px * 0.18f
        })
    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}

// ── Formatters ────────────────────────────────────────────────────────────────

private fun formatRupiah(amount: Double): String {
    val fmt = NumberFormat.getNumberInstance(Locale("id", "ID"))
    return "Rp ${fmt.format(amount.roundToInt())}"
}