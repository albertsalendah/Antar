package com.richard_salendah.antar.ui.trip

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import com.richard_salendah.antar.ui.common.OfflineBanner
import com.richard_salendah.antar.ui.common.rememberConnectivityState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
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
    val minSheetHeight: Dp = 280.dp

    var mapView        by remember { mutableStateOf<MapView?>(null) }
    var sheetExpansion by remember { mutableFloatStateOf(0f) }
    var dragAccum      by remember { mutableFloatStateOf(0f) }
    val sheetHeight    = minSheetHeight + (maxSheetHeight - minSheetHeight) * sheetExpansion

    val trip                 = viewModel.trip
    val driverLocation       by viewModel.driverLocation.collectAsState()
    val routePoints          = viewModel.routePoints
    val routeDistanceMeters  = viewModel.routeDistanceMeters
    val routeDurationSeconds = viewModel.routeDurationSeconds

    // CONN-3: observe connectivity state to show OfflineBanner and trigger retryRoute
    val isOnline by rememberConnectivityState()
    var wasOffline by remember { mutableStateOf(false) }

    // CONN-5: track elapsed time since last status update for stale hint
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000L)
            now = System.currentTimeMillis()
        }
    }
    val secondsSinceUpdate = (now - viewModel.lastStatusUpdateMs) / 1000L
    val showStaleHint = secondsSinceUpdate > 30 && trip?.status != "completed"

    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
        viewModel.start(tripId, onTripCompleted)
    }

    // CONN-4: when connectivity is restored after an outage, reset route
    // fetch state so the polyline is re-fetched with fresh driver location.
    LaunchedEffect(isOnline) {
        if (isOnline && wasOffline) {
            viewModel.retryRoute()
        }
        wasOffline = !isOnline
    }

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

    LaunchedEffect(mapView, driverLocation, trip, routePoints) {
        val map = mapView ?: return@LaunchedEffect
        updateOverlays(map, trip, driverLocation, routePoints)
    }

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
                    .padding(bottom = sheetHeight-40.dp, end = 16.dp)
                    .size(44.dp),
            ) {
                Icon(Icons.Default.MyLocation, "Ikuti driver", modifier = Modifier.size(22.dp))
            }
        }

        // ── Distance/ETA info card ────────────────────────────────────────────
        // Shown for agreed and in_progress; hidden on arrived (driver is waiting).
        if (routeDistanceMeters != null &&
            (trip?.status == "agreed" || trip?.status == "in_progress")) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp),
                shape           = RoundedCornerShape(12.dp),
                color           = Color.White,
                shadowElevation = 4.dp,
            ) {
                Row(
                    modifier              = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Default.LocationOn, null,
                        tint     = PrimaryBlue,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        formatDistance(routeDistanceMeters),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    routeDurationSeconds?.let { duration ->
                        Text(
                            "•",
                            color = Color(0xFFBBBBBB),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            formatDuration(duration),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    }
                }
            }
        }

        // ── Draggable bottom sheet ────────────────────────────────────────────
        var dragAccumSheet by remember { mutableFloatStateOf(0f) }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color.White),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // CONN-3: OfflineBanner at the very top of the sheet
                OfflineBanner()

                // CONN-3: connection-lost warning when polling has repeatedly failed
                if (viewModel.connectionLost && isOnline) {
                    Card(
                        modifier  = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape     = RoundedCornerShape(10.dp),
                        colors    = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                        elevation = CardDefaults.cardElevation(0.dp),
                    ) {
                        Row(
                            modifier  = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.WifiOff, null,
                                tint     = Amber,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                "Koneksi bermasalah — data mungkin tidak terkini",
                                style    = MaterialTheme.typography.labelSmall.copy(color = Amber),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // CONN-5: stale status hint after 30 s without an update
                if (showStaleHint) {
                    Card(
                        modifier  = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape     = RoundedCornerShape(10.dp),
                        colors    = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5)),
                        elevation = CardDefaults.cardElevation(0.dp),
                    ) {
                        Row(
                            modifier  = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                Icons.Default.Refresh, null,
                                tint     = Color(0xFF7B1FA2),
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                "Perjalanan mungkin sudah selesai, tarik untuk refresh",
                                style    = MaterialTheme.typography.labelSmall.copy(
                                    color = Color(0xFF7B1FA2)),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // Drag handle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart  = { dragAccumSheet = 0f },
                                onDragEnd    = {
                                    sheetExpansion =
                                        if (sheetExpansion > 0.4f) 1f else 0f
                                },
                                onVerticalDrag = { _, delta ->
                                    dragAccumSheet += delta
                                    val maxPx = with(density) {
                                        (maxSheetHeight - minSheetHeight).toPx()
                                    }
                                    sheetExpansion =
                                        (sheetExpansion - dragAccumSheet / maxPx)
                                            .coerceIn(0f, 1f)
                                    dragAccumSheet = 0f
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
                        StatusStepper(status = trip.status)

                        Spacer(Modifier.height(8.dp))

                        // Status message cards
                        when (trip.status) {
                            "agreed" -> Card(
                                modifier  = Modifier.fillMaxWidth(),
                                shape     = RoundedCornerShape(10.dp),
                                colors    = CardDefaults.cardColors(
                                    containerColor = Color(0xFFE8F4FD)),
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
                                            color = PrimaryBlue, fontWeight = FontWeight.Medium),
                                    )
                                }
                            }
                            "arrived" -> Card(
                                modifier  = Modifier.fillMaxWidth(),
                                shape     = RoundedCornerShape(10.dp),
                                colors    = CardDefaults.cardColors(
                                    containerColor = AmberLight),
                                elevation = CardDefaults.cardElevation(0.dp),
                            ) {
                                Row(
                                    modifier  = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        Icons.Default.DirectionsCar, null,
                                        tint     = Amber,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        "Driver sudah tiba! Segera menuju kendaraan",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = Amber, fontWeight = FontWeight.Bold),
                                    )
                                }
                            }
                            "in_progress" -> Card(
                                modifier  = Modifier.fillMaxWidth(),
                                shape     = RoundedCornerShape(10.dp),
                                colors    = CardDefaults.cardColors(
                                    containerColor = GreenLight),
                                elevation = CardDefaults.cardElevation(0.dp),
                            ) {
                                Row(
                                    modifier  = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Icon(
                                        Icons.Default.DirectionsCar, null,
                                        tint     = Green,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        "Perjalanan berlangsung — Anda di dalam kendaraan",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = Green, fontWeight = FontWeight.Medium),
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Expanded detail
                        if (sheetExpansion > 0.1f) {
                            Card(
                                modifier  = Modifier.fillMaxWidth(),
                                shape     = RoundedCornerShape(12.dp),
                                colors    = CardDefaults.cardColors(
                                    containerColor = Color(0xFFF8F8F8)),
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
                                    containerColor = Color(0xFFF8F8F8)),
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

                        // Driver info bar
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

    // Pickup pin — teardrop, hidden when rider is already in the vehicle
    if (trip.pickupLat != 0.0 && status != "in_progress") {
        Marker(map).apply {
            id       = "pickup"
            position = GeoPoint(trip.pickupLat, trip.pickupLng)
            title    = "Penjemputan"
            snippet  = trip.pickupAddress
            icon     = pinDrawable(map.context, 0xFF1B6CA8.toInt(), 30)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            map.overlays.add(this)
        }
    }

    // Dropoff pin — teardrop, shown only when in_progress on transport trips
    if (status == "in_progress" &&
        trip.tripType == "transport" &&
        trip.dropoffLat != 0.0) {
        Marker(map).apply {
            id       = "dropoff"
            position = GeoPoint(trip.dropoffLat, trip.dropoffLng)
            title    = "Tujuan"
            snippet  = trip.dropoffAddress ?: ""
            icon     = pinDrawable(map.context, 0xFFE53935.toInt(), 30)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            map.overlays.add(this)
        }
    }

    // Driver pin — circle, moving position marker
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

// ── Marker drawables ──────────────────────────────────────────────────────────

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

/**
 * Teardrop map-pin for static pickup and dropoff markers.
 * Anchored at ANCHOR_CENTER / ANCHOR_BOTTOM (the tip of the tail).
 * Moving position markers (driver) use [circleDrawable] instead.
 */
private fun pinDrawable(
    context:  android.content.Context,
    colorInt: Int,
    sizeDp:   Int,
): android.graphics.drawable.BitmapDrawable {
    val density = context.resources.displayMetrics.density
    val w  = (sizeDp * density).coerceAtLeast(8f)
    val h  = w * 1.35f
    val cx = w / 2f
    val r  = w / 2f

    val bmp    = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val fill   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInt; style = Paint.Style.FILL
    }

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
                        isActive && status == "arrived" -> Amber
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
                            isActive && status == "arrived" -> Amber
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

// ── Formatters ────────────────────────────────────────────────────────────────

private fun formatRupiah(amount: Double) =
    "Rp " + NumberFormat.getNumberInstance(Locale("id", "ID")).format(amount.toLong())

/** "650 m" below 1 km, "3.2 km" at or above. */
private fun formatDistance(meters: Double): String = when {
    meters < 1_000 -> "${meters.roundToInt()} m"
    else            -> "%.1f km".format(meters / 1_000)
}

/** "8 min" below an hour, "1h 15m" at or above. */
private fun formatDuration(seconds: Double): String {
    val totalMinutes = (seconds / 60).roundToInt()
    return if (totalMinutes < 60) "$totalMinutes min"
    else "${totalMinutes / 60}h ${totalMinutes % 60}m"
}