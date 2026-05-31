package com.richard_salendah.antar.ui.trip

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.richard_salendah.antar.data.model.TripResponse
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.NumberFormat
import java.util.Locale

private val PrimaryBlue = Color(0xFF1B6CA8)
private val Red         = Color(0xFFE53935)
private val Green       = Color(0xFF2E7D32)
private val GreenLight  = Color(0xFFE8F5E9)
private val AmberLight  = Color(0xFFFFF8E1)
private val Amber       = Color(0xFFF57F17)

@Composable
fun ActiveTripScreen(
    tripId: String,
    onTripCompleted: () -> Unit,
    viewModel: ActiveTripViewModel = viewModel(),
) {
    val context        = LocalContext.current
    val lifecycle      = LocalLifecycleOwner.current.lifecycle
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val density        = LocalDensity.current

    val maxSheetHeight: Dp = screenHeightDp * 0.75f
    val minSheetHeight: Dp = 195.dp

    var mapView        by remember { mutableStateOf<MapView?>(null) }
    var sheetExpansion by remember { mutableFloatStateOf(0f) }
    var dragAccum      by remember { mutableFloatStateOf(0f) }
    val sheetHeight    = minSheetHeight + (maxSheetHeight - minSheetHeight) * sheetExpansion

    val trip           = viewModel.trip
    val driverLocation by viewModel.driverLocation.collectAsState()
    val routePoints    = viewModel.routePoints

    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
        viewModel.start(tripId, onTripCompleted)
    }

    // Centre map on driver; midpoint driver+dropoff when in_progress
    LaunchedEffect(driverLocation, trip?.status) {
        val map = mapView ?: return@LaunchedEffect
        val loc = driverLocation ?: return@LaunchedEffect
        when (trip?.status) {
            "agreed", "arrived" -> map.controller.animateTo(GeoPoint(loc.lat, loc.lng))
            "in_progress" -> {
                if (trip.dropoffLat != 0.0) {
                    val midLat = (loc.lat + trip.dropoffLat) / 2
                    val midLng = (loc.lng + trip.dropoffLng) / 2
                    map.controller.animateTo(GeoPoint(midLat, midLng))
                } else {
                    map.controller.animateTo(GeoPoint(loc.lat, loc.lng))
                }
            }
        }
    }

    // Refresh map overlays
    LaunchedEffect(driverLocation, trip, routePoints) {
        val map = mapView ?: return@LaunchedEffect
        updateOverlays(map, trip, driverLocation, routePoints)
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
                    controller.setZoom(16.0)
                    isHorizontalMapRepetitionEnabled = false
                    isVerticalMapRepetitionEnabled   = false
                }.also { mapView = it }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ── Recenter FAB ──────────────────────────────────────────────────────
        if (driverLocation != null) {
            FloatingActionButton(
                onClick = {
                    val loc = driverLocation ?: return@FloatingActionButton
                    mapView?.controller?.animateTo(GeoPoint(loc.lat, loc.lng))
                },
                shape          = CircleShape,
                containerColor = Color.White,
                contentColor   = PrimaryBlue,
                elevation      = FloatingActionButtonDefaults.elevation(4.dp),
                modifier       = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(bottom = sheetHeight + 12.dp, end = 16.dp)
                    .size(44.dp),
            ) {
                Icon(Icons.Default.MyLocation, "Ikuti driver", modifier = Modifier.size(22.dp))
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

                if (trip == null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator(color = PrimaryBlue) }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        // ── Status stepper ────────────────────────────────────
                        StatusStepper(status = trip.status)

                        Spacer(Modifier.height(8.dp))

                        // ── Status message card ───────────────────────────────
                        when (trip.status) {
                            "agreed" -> Card(
                                modifier  = Modifier.fillMaxWidth(),
                                shape     = RoundedCornerShape(10.dp),
                                colors    = CardDefaults.cardColors(
                                    containerColor = Color(0xFFE8F4FD)
                                ),
                                elevation = CardDefaults.cardElevation(0.dp),
                            ) {
                                Row(
                                    modifier  = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CircularProgressIndicator(
                                        color       = PrimaryBlue,
                                        modifier    = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Text(
                                        "Driver sedang menuju lokasi penjemputan Anda",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color      = PrimaryBlue,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                    )
                                }
                            }

                            // ── arrived: driver is at pickup ──────────────────
                            "arrived" -> Card(
                                modifier  = Modifier.fillMaxWidth(),
                                shape     = RoundedCornerShape(10.dp),
                                colors    = CardDefaults.cardColors(
                                    containerColor = AmberLight
                                ),
                                elevation = CardDefaults.cardElevation(0.dp),
                            ) {
                                Row(
                                    modifier  = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        Icons.Default.DirectionsCar,
                                        contentDescription = null,
                                        tint     = Amber,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        "Driver sudah tiba! Segera menuju kendaraan",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color      = Amber,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                    )
                                }
                            }

                            "in_progress" -> Card(
                                modifier  = Modifier.fillMaxWidth(),
                                shape     = RoundedCornerShape(10.dp),
                                colors    = CardDefaults.cardColors(
                                    containerColor = GreenLight
                                ),
                                elevation = CardDefaults.cardElevation(0.dp),
                            ) {
                                Row(
                                    modifier  = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        Icons.Default.DirectionsCar,
                                        contentDescription = null,
                                        tint     = Green,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        "Perjalanan berlangsung — Anda di dalam kendaraan",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color      = Green,
                                            fontWeight = FontWeight.Medium,
                                        ),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // ── Expanded: fare + route detail ─────────────────────
                        if (sheetExpansion > 0.1f) {
                            Card(
                                modifier  = Modifier.fillMaxWidth(),
                                shape     = RoundedCornerShape(12.dp),
                                colors    = CardDefaults.cardColors(
                                    containerColor = Color(0xFFF8F8F8)
                                ),
                                elevation = CardDefaults.cardElevation(0.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment     = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(
                                            "Total Ongkos",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = Color(0xFF999999)),
                                        )
                                        Text(
                                            formatRupiah(trip.fare ?: trip.offeredFare ?: 0.0),
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                color = PrimaryBlue, fontWeight = FontWeight.Bold),
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFFE8F4FD),
                                    ) {
                                        Text(
                                            trip.paymentMethod.replaceFirstChar { it.uppercase() },
                                            style    = MaterialTheme.typography.labelMedium.copy(
                                                color = PrimaryBlue,
                                                fontWeight = FontWeight.SemiBold),
                                            modifier = Modifier.padding(
                                                horizontal = 10.dp, vertical = 6.dp),
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Card(
                                modifier  = Modifier.fillMaxWidth(),
                                shape     = RoundedCornerShape(12.dp),
                                colors    = CardDefaults.cardColors(
                                    containerColor = Color(0xFFF8F8F8)
                                ),
                                elevation = CardDefaults.cardElevation(0.dp),
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    DetailRow(
                                        icon     = Icons.Default.LocationOn,
                                        iconTint = PrimaryBlue,
                                        label    = "Penjemputan",
                                        value    = trip.pickupAddress,
                                    )
                                    if (trip.tripType == "transport" &&
                                        !trip.dropoffAddress.isNullOrBlank()) {
                                        HorizontalDivider(
                                            Modifier.padding(vertical = 10.dp),
                                            color = Color(0xFFF0F0F0),
                                        )
                                        DetailRow(
                                            icon     = Icons.Default.LocationOn,
                                            iconTint = Red,
                                            label    = "Tujuan",
                                            value    = trip.dropoffAddress!!,
                                        )
                                    } else if (trip.tripType == "errand" &&
                                        !trip.note.isNullOrBlank()) {
                                        HorizontalDivider(
                                            Modifier.padding(vertical = 10.dp),
                                            color = Color(0xFFF0F0F0),
                                        )
                                        DetailRow(
                                            icon     = Icons.Default.DirectionsCar,
                                            iconTint = Color(0xFF777777),
                                            label    = "Keterangan",
                                            value    = trip.note!!,
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                        }

                        // ── Always visible: driver bar ────────────────────────
                        Surface(
                            modifier        = Modifier.fillMaxWidth(),
                            shape           = RoundedCornerShape(12.dp),
                            color           = Color.White,
                            shadowElevation = 2.dp,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Surface(
                                    shape    = CircleShape,
                                    color    = Color(0xFFE8F4FD),
                                    modifier = Modifier.size(44.dp),
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier         = Modifier.fillMaxSize(),
                                    ) {
                                        Icon(
                                            Icons.Default.Person, null,
                                            tint     = PrimaryBlue,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Driver Anda",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = Color(0xFF999999)),
                                    )
                                    Text(
                                        trip.driverName.ifEmpty {
                                            trip.driverId?.takeLast(6)?.uppercase() ?: "—"
                                        },
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color      = Color(0xFF1A1A1A)),
                                    )
                                    if (trip.driverPhone.isNotEmpty()) {
                                        Text(
                                            trip.driverPhone,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = Color(0xFF888888)),
                                        )
                                    }
                                }
                                FilledIconButton(
                                    onClick = {
                                        val phone  = trip.driverPhone.ifEmpty { null }
                                        val intent = if (phone != null)
                                            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
                                        else Intent(Intent.ACTION_DIAL)
                                        context.startActivity(intent)
                                    },
                                    colors   = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = PrimaryBlue),
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Call, "Hubungi driver",
                                        tint     = Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Map overlays ──────────────────────────────────────────────────────────────

private fun updateOverlays(
    map:         MapView,
    trip:        TripResponse?,
    driverLoc:   DriverLocation?,
    routePoints: List<GeoPoint>,
) {
    map.overlays.removeAll { it is Marker || it is Polyline }

    if (trip == null) { map.invalidate(); return }

    val status = trip.status

    // Route line
    if (routePoints.isNotEmpty()) {
        val lineColor = if (status == "in_progress")
            android.graphics.Color.parseColor("#E53935")
        else
            android.graphics.Color.parseColor("#1B6CA8")
        Polyline(map).apply {
            setPoints(routePoints)
            outlinePaint.apply {
                color       = lineColor
                strokeWidth = 8f
                isAntiAlias = true
                strokeCap   = android.graphics.Paint.Cap.ROUND
                strokeJoin  = android.graphics.Paint.Join.ROUND
            }
            map.overlays.add(this)
        }
    }

    // Pickup pin — always visible
    if (trip.pickupLat != 0.0) {
        Marker(map).apply {
            id       = "pickup"
            position = GeoPoint(trip.pickupLat, trip.pickupLng)
            title    = "Penjemputan"
            snippet  = trip.pickupAddress
            icon     = circleDrawable(map.context, 0xFF1B6CA8.toInt(), 30)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            map.overlays.add(this)
        }
    }

    // Dropoff pin — transport + in_progress only
    if (status == "in_progress" &&
        trip.tripType == "transport" &&
        trip.dropoffLat != 0.0) {
        Marker(map).apply {
            id       = "dropoff"
            position = GeoPoint(trip.dropoffLat, trip.dropoffLng)
            title    = "Tujuan"
            snippet  = trip.dropoffAddress ?: ""
            icon     = circleDrawable(map.context, 0xFFE53935.toInt(), 30)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            map.overlays.add(this)
        }
    }

    // Driver/vehicle pin
    // When in_progress rider is inside the vehicle — show combined green marker
    // When arrived — show amber marker to indicate driver is waiting at pickup
    driverLoc?.let { loc ->
        val (pinColor, pinTitle) = when (status) {
            "arrived"     -> Pair(0xFFF57F17.toInt(), "Driver menunggu Anda")
            "in_progress" -> Pair(0xFF2E7D32.toInt(), "Kendaraan Anda")
            else          -> Pair(0xFF03A9F4.toInt(), trip.driverName.ifEmpty { "Driver" })
        }
        Marker(map).apply {
            id       = "driver"
            position = GeoPoint(loc.lat, loc.lng)
            title    = pinTitle
            icon     = circleDrawable(map.context, pinColor, 32)
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

// ── Status stepper ────────────────────────────────────────────────────────────

@Composable
private fun StatusStepper(status: String) {
    val steps = listOf(
        Triple("agreed",      "Driver ditemukan",  Icons.Default.Check),
        Triple("arrived",     "Driver tiba",       Icons.Default.DirectionsCar),
        Triple("in_progress", "Dalam perjalanan",  Icons.Default.DirectionsCar),
        Triple("completed",   "Sampai tujuan",     Icons.Default.LocationOn),
    )
    val currentIndex = steps.indexOfFirst { it.first == status }.coerceAtLeast(0)

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
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
                    color    = when {
                        isActive && status == "arrived" -> Color(0xFFF57F17) // amber for arrived
                        isDone                          -> PrimaryBlue
                        else                            -> Color(0xFFEEEEEE)
                    },
                    modifier = Modifier.size(if (isActive) 36.dp else 28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        if (isActive && status != "completed") {
                            CircularProgressIndicator(
                                color       = Color.White,
                                modifier    = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                icon, null,
                                tint     = if (isDone) Color.White else Color(0xFFBBBBBB),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = when {
                            isActive && status == "arrived" -> Color(0xFFF57F17)
                            isDone                          -> PrimaryBlue
                            else                            -> Color(0xFFAAAAAA)
                        },
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    ),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            if (index < steps.lastIndex) {
                Box(
                    modifier = Modifier
                        .weight(0.2f).height(2.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            if (index < currentIndex) PrimaryBlue else Color(0xFFEEEEEE)
                        ),
                )
            }
        }
    }
}

// ── Detail row ────────────────────────────────────────────────────────────────

@Composable
private fun DetailRow(
    icon:     androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    label:    String,
    value:    String,
) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(16.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFFAAAAAA)),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = Color(0xFF1A1A1A), fontWeight = FontWeight.Medium),
            )
        }
    }
}

private fun formatRupiah(amount: Double) =
    "Rp " + NumberFormat.getNumberInstance(Locale("id", "ID")).format(amount.toLong())