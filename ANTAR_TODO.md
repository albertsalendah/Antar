# Antar — TODO List
**Last updated:** May 2026
**Paste alongside ANTAR_CONTEXT.md when working on new features.**

---

## Priority Order

1. [TODO-1] Add pickup/dropoff coords to rider `TripResponse` (server + Android) — *unblocks map routing*
2. [TODO-2] Driver `UpdateLocation` — write `last_lat`/`last_lng` columns — *unblocks Option B*
3. [TODO-3] Driver `ActiveTripScreen` — add map + routing (matches rider app)
4. [TODO-4] Option B — Realtime driver location in rider app *(do when app is stable)*

---

## TODO-1 — Add pickup/dropoff coords to rider TripResponse

**Why:** Rider `ActiveTripScreen` has a map but `TripResponse` has no pickup/dropoff coordinates, so it can't draw pickup/dropoff pins or compute OSRM routes. The trips table stores these as PostGIS geography columns — they need to be extracted with `ST_Y`/`ST_X` in the query.

### Server — `internal/rider/handler.go`

In the `tripSelect` constant, add 4 columns after the `driver_lat`/`driver_lng` lines:

```go
// Add after:  COALESCE(ST_X(dp.last_location::geometry), 0) AS driver_lng,
COALESCE(ST_Y(t.pickup_location::geometry),  0) AS pickup_lat,
COALESCE(ST_X(t.pickup_location::geometry),  0) AS pickup_lng,
COALESCE(ST_Y(t.dropoff_location::geometry), 0) AS dropoff_lat,
COALESCE(ST_X(t.dropoff_location::geometry), 0) AS dropoff_lng,
```

In `scanTrip()`, add 4 scan targets after `&t.DriverLat, &t.DriverLng`:
```go
&t.PickupLat, &t.PickupLng, &t.DropoffLat, &t.DropoffLng,
```

### Server — `internal/rider/model.go`

Add to `TripResponse` struct:
```go
PickupLat  float64 `json:"pickup_lat"`
PickupLng  float64 `json:"pickup_lng"`
DropoffLat float64 `json:"dropoff_lat"`
DropoffLng float64 `json:"dropoff_lng"`
```

### Android — `data/remote/model/Models.kt`

Add to `TripResponse` data class:
```kotlin
@SerializedName("pickup_lat")  val pickupLat:  Double = 0.0,
@SerializedName("pickup_lng")  val pickupLng:  Double = 0.0,
@SerializedName("dropoff_lat") val dropoffLat: Double = 0.0,
@SerializedName("dropoff_lng") val dropoffLng: Double = 0.0,
```

---

## TODO-2 — Driver UpdateLocation — write last_lat/last_lng

**Why:** `driver_profiles` already has `last_lat` and `last_lng` double precision columns (confirmed in DB). The Go handler currently only writes `last_location` (PostGIS geography). Writing the plain float columns enables Option B Realtime — the Supabase SDK can broadcast clean float values over WebSocket without needing PostGIS deserialization on the client.

### Server — `internal/driver/handler.go`

In `UpdateLocation`, change the SQL in `h.db.Exec`:
```go
result, err := h.db.Exec(context.Background(),
    `UPDATE driver_profiles
     SET last_location = ST_SetSRID(ST_MakePoint($1,$2),4326),
         last_lat      = $2,
         last_lng      = $1,
         island_id     = resolve_island_id($1, $2),
         updated_at    = $3,
         is_online     = true
     WHERE id = $4`,
    req.Longitude, req.Latitude, time.Now(), driverID,
)
```
*(Note: PostGIS MakePoint takes lng first, lat second. last_lat = $2 = Latitude, last_lng = $1 = Longitude.)*

---

## TODO-3 — Driver ActiveTripScreen — Map + Routing

**Why:** Driver `ActiveTripScreen` exists with trip detail UI but has no map. It needs to show driver position + pickup/dropoff pins + OSRM road route, consistent with the rider app.

`DriverTripResponse` already has `pickup_lat`, `pickup_lng`, `dropoff_lat`, `dropoff_lng` — no server change needed.

### Step 1 — Create `RouteHelper.kt` in `ui/trip/`

New file. Identical logic to rider app's `OsrmRouteHelper.kt`:

```kotlin
package com.richard_salendah.driverantar.ui.trip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

object RouteHelper {

    suspend fun fetchRoute(
        originLat: Double, originLng: Double,
        destLat: Double, destLng: Double,
    ): List<GeoPoint>? = withContext(Dispatchers.IO) {
        if (originLat == 0.0 && originLng == 0.0) return@withContext null
        if (destLat == 0.0 && destLng == 0.0) return@withContext null
        runCatching {
            val url = "https://router.project-osrm.org/route/v1/driving/" +
                "$originLng,$originLat;$destLng,$destLat" +
                "?overview=full&geometries=geojson"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "AntarDriverApp/1.0")
            conn.connectTimeout = 6_000
            conn.readTimeout    = 6_000
            val json   = JSONObject(conn.inputStream.bufferedReader().readText())
            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) return@runCatching null
            val coords = routes.getJSONObject(0)
                .getJSONObject("geometry")
                .getJSONArray("coordinates")
            (0 until coords.length()).map { i ->
                val pt = coords.getJSONArray(i)
                GeoPoint(pt.getDouble(1), pt.getDouble(0)) // OSRM gives [lng, lat]
            }
        }.getOrNull()
    }

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        if (lat2 == 0.0 && lng2 == 0.0) return Double.MAX_VALUE
        val r    = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a    = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
```

### Step 2 — Update `ActiveTripViewModel.kt` (driver)

Add route state and fetch logic alongside existing trip + Realtime code:

```kotlin
// Add these state vars:
var routePoints by mutableStateOf<List<GeoPoint>>(emptyList()); private set
private var lastRouteFetchLat = 0.0
private var lastRouteFetchLng = 0.0

// Add this function:
fun fetchRouteIfNeeded(driverLat: Double, driverLng: Double) {
    val t = trip ?: return
    val moved = RouteHelper.distanceMeters(driverLat, driverLng, lastRouteFetchLat, lastRouteFetchLng)
    if (moved < 50.0 && routePoints.isNotEmpty()) return

    val (destLat, destLng) = when (t.status) {
        "agreed"      -> Pair(t.pickup_lat, t.pickup_lng)
        "in_progress" -> if (t.dropoff_lat != 0.0) Pair(t.dropoff_lat, t.dropoff_lng)
                         else Pair(t.pickup_lat, t.pickup_lng)
        else          -> return
    }
    if (destLat == 0.0 && destLng == 0.0) return

    lastRouteFetchLat = driverLat
    lastRouteFetchLng = driverLng
    viewModelScope.launch {
        val pts = RouteHelper.fetchRoute(driverLat, driverLng, destLat, destLng)
        if (pts != null) routePoints = pts
    }
}
```

In `init {}`, observe `LocationService.locationFlow` to trigger route updates:
```kotlin
viewModelScope.launch {
    LocationService.locationFlow.collect { location ->
        fetchRouteIfNeeded(location.latitude, location.longitude)
    }
}
```

### Step 3 — Rewrite `ActiveTripScreen.kt` (driver)

Replace the existing screen with full-screen OSMDroid map + draggable bottom sheet (same pattern as rider `ActiveTripScreen`).

**Layout structure:**
```
Box(fillMaxSize) {
    AndroidView(MapView, fillMaxSize)         ← full-screen map
    FloatingActionButton(recenter, bottomEnd) ← above sheet
    Box(bottomSheet, draggable) {
        DragHandle
        Column(scrollable) {
            // Collapsed: TripStatusHeader + DriverRiderInfoCard + ActionButtons
            // Expanded: + FareCard + AddressCard
        }
    }
}
CancelConfirmDialog (if uiState == Confirming)
```

**Map overlay update function** (call from `LaunchedEffect(driverGeoPoint, trip, routePoints)`):
```kotlin
private fun updateOverlays(
    map: MapView,
    trip: DriverTripResponse?,
    driverGeoPoint: GeoPoint,
    routePoints: List<GeoPoint>,
) {
    map.overlays.removeAll { it is Marker || it is Polyline }

    // Route line
    if (routePoints.isNotEmpty()) {
        val lineColor = if (trip?.status == "in_progress")
            android.graphics.Color.parseColor("#E53935")
        else android.graphics.Color.parseColor("#1B6CA8")
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

    // Pickup pin (always visible)
    trip?.let { t ->
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
        // Dropoff pin (transport + in_progress only)
        if (t.status == "in_progress" && t.trip_type == "transport" && t.dropoff_lat != 0.0) {
            Marker(map).apply {
                position = GeoPoint(t.dropoff_lat, t.dropoff_lng)
                title    = "Tujuan"
                snippet  = t.dropoff_address ?: ""
                icon     = circleDrawable(map.context, 0xFFE53935.toInt(), 30)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(this)
            }
        }
    }

    // Driver pin (moving)
    if (driverGeoPoint.latitude != 0.0) {
        Marker(map).apply {
            position = driverGeoPoint
            title    = "Posisi Anda"
            icon     = circleDrawable(map.context, 0xFF03A9F4.toInt(), 32)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            map.overlays.add(this)
        }
    }
    map.invalidate()
}

// circleDrawable helper (same as rider app)
private fun circleDrawable(context: android.content.Context, colorInt: Int, sizeDp: Int)
    : android.graphics.drawable.BitmapDrawable {
    val px  = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(8)
    val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val cvs = android.graphics.Canvas(bmp)
    cvs.drawCircle(px / 2f, px / 2f, px / 2f,
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = colorInt })
    cvs.drawCircle(px / 2f, px / 2f, px / 2f - px * 0.09f,
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color       = android.graphics.Color.WHITE
            style       = android.graphics.Paint.Style.STROKE
            strokeWidth = px * 0.18f
        })
    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}
```

**Bottom sheet content (collapsed — always visible):**
- Trip status pill (agreed = "Menuju Penumpang", in_progress = "Perjalanan Berlangsung")
- Rider name + phone + call button
- Fare + payment method
- Start / Complete / Cancel buttons (based on status)

**Bottom sheet content (expanded — additional):**
- Pickup address row
- Dropoff address row (transport) or errand note card (errand)

---

## TODO-4 — Option B: Realtime Driver Location in Rider App

**Do this after the app is stable on production.**
**Prerequisites:** TODO-2 must be done first (last_lat/last_lng written by server).

### Step 1 — Supabase SQL (one migration)
```sql
-- Allow riders to subscribe to driver location changes
CREATE POLICY "riders can read driver location"
ON driver_profiles FOR SELECT
TO authenticated
USING (true);
```
*(trips table is already in the Realtime publication from migration `20260426175721`)*
*(Add driver_profiles to Realtime publication via Supabase Dashboard → Database → Replication)*

### Step 2 — Implement `RealtimeLocationTracker` in rider `LocationTracker.kt`

Replace the stub `RealtimeLocationTracker` class:
```kotlin
class RealtimeLocationTracker(
    private val api: ApiService,
    private val supabase: SupabaseClient,
    private val driverProfileId: String,
) : LocationTracker {
    private val pollingFallback = PollingLocationTracker(api, intervalMs = 15_000L)
    private val _location       = MutableStateFlow<DriverLocation?>(null)
    override val location: StateFlow<DriverLocation?> = _location.asStateFlow()

    override fun start(tripId: String, scope: CoroutineScope) {
        pollingFallback.start(tripId, scope)
        scope.launch {
            runCatching {
                val ch = supabase.channel("driver-loc-$driverProfileId-${System.currentTimeMillis()}")
                ch.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "driver_profiles"
                    filter("id", FilterOperator.EQ, driverProfileId)
                }.onEach { action ->
                    val lat = action.record["last_lat"]?.jsonPrimitive?.doubleOrNull ?: return@onEach
                    val lng = action.record["last_lng"]?.jsonPrimitive?.doubleOrNull ?: return@onEach
                    _location.value = DriverLocation(lat, lng)
                }.launchIn(scope)
                ch.subscribe()
            }
        }
    }

    override fun stop() { pollingFallback.stop() }
}
```

### Step 3 — Update `ActiveTripViewModel` (rider) — change one line

```kotlin
// BEFORE
private val locationTracker: LocationTracker = PollingLocationTracker(api, intervalMs = 3_000L)

// AFTER (get driverProfileId from trip.driverId once trip loads)
private val locationTracker: LocationTracker = RealtimeLocationTracker(
    api             = api,
    supabase        = supabase,
    driverProfileId = trip?.driverId ?: ""
)
```
