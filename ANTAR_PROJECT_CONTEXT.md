# Antar — Project Context Handover
**Last updated:** May 2026 — Active trip map + routing implementation phase  
**Purpose:** Paste this entire file into a new Claude session before asking anything.

---

## 1. What This Project Is

Antar is a Grab/GoJek-style ride-hailing app for the **Kepulauan Talaud archipelago, North Sulawesi, Indonesia**. Two Android apps (driver + rider) backed by a single Go server.

**Key local adaptations:**
- Island isolation: riders and drivers matched only within the same island via PostGIS polygon boundaries
- Price negotiation: driver offers price, rider can accept / reject / counter-offer (max rounds admin-configurable)
- Trip types: `transport` (normal ride) and `errand` (driver buys/fetches something for rider)
- Vehicle type matching: rider picks vehicle_type_id; only matching drivers see the trip
- Sequential FCM: nearest driver notified first, 3-min timeout, pg_cron tries next, auto-cancels after 5 attempts

---

## 2. Tech Stack

| Layer | Technology |
|---|---|
| Server | Go (Gin), pgx/v5, Supabase Auth + Storage + Realtime, Firebase FCM HTTP v1, pg_cron |
| Database | PostgreSQL 17 + PostGIS on Supabase |
| Driver Android | Kotlin, Compose, Retrofit + OkHttp, OSMDroid, FusedLocationProvider, Supabase Kotlin SDK (Realtime), Firebase FCM |
| Rider Android | Kotlin, Compose, Retrofit + OkHttp, OSMDroid, FusedLocationProvider, Supabase Kotlin SDK (Realtime), Firebase FCM, DataStore |

**Supabase project:** ID `lbiijuuugqgcfrpksilh`, region `ap-southeast-1`, name `antar`

---

## 3. Current Build Status

### Server — ✅ COMPLETE
All endpoints implemented and working. All 18+ migrations applied to Supabase.

### Driver Android App (`DriverAntar/`) — ✅ COMPLETE
All screens built and working end-to-end:
- Auth (login, register)
- Map screen (online/offline toggle, GPS tracking foreground service)
- Profile (avatar, vehicles, island badge, rating)
- Incoming trips (poll every 5s, skeleton loading)
- Offer price (fare stepper, floor enforcement)
- Waiting for rider (Supabase Realtime subscription)
- Counter decision (accept/counter/reject rider's counter-offer)
- Active trip (**map needed** — see Section 10)
- Rate rider
- Trip history (paginated)
- Earnings (7-day bar chart, summary cards)
- Offline banner, shimmer skeletons, haptic feedback

### Rider Android App (`Antar/`) — ✅ COMPLETE (map routing pending)
All screens built and working end-to-end:
- Auth (login, register with email confirm dialog)
- Home (OSMDroid map, vehicle chips with online count, booking in bottom sheet, pin-tap location)
- Searching (radar animation, Realtime + polling)
- Negotiation (fare stepper, counter input, Realtime + polling)
- Active trip (OSMDroid map behind draggable sheet, driver pin — **route line + pickup/dropoff pins pending**)
- Trip complete + rate driver
- Trip history (shimmer, paginated)
- Profile (avatar, island badge, edit inline)
- Offline banner, haptic feedback, FCM deep linking

---

## 4. Repository Structure

```
/
├── Antar-Server/
│   ├── main.go
│   ├── config/config.go
│   ├── internal/
│   │   ├── admin/handler.go, model.go, routes.go
│   │   ├── driver/handler.go, model.go, routes.go
│   │   ├── rider/handler.go, model.go, routes.go
│   │   └── middleware/auth_middleware.go
│   ├── pkg/
│   │   ├── database/database.go
│   │   ├── fcm/fcm.go
│   │   ├── notification/processor.go
│   │   ├── response/response.go
│   │   └── supabase/auth.go, storage.go
│   └── supabase/migrations/ (001-018, all applied)
├── Antar/                   ← Rider app
│   └── app/src/main/java/com/richard_salendah/antar/
│       ├── Antar.kt         ← Application class
│       ├── MainActivity.kt
│       ├── navigation/Screen.kt, NavGraph.kt, DeepLinkHandler.kt
│       ├── data/
│       │   ├── local/SessionManager.kt   (DataStore)
│       │   └── remote/ApiClient.kt, ApiService.kt, Models.kt
│       ├── service/RiderFirebaseMessagingService.kt
│       └── ui/
│           ├── theme/Color.kt, Theme.kt, Type.kt
│           ├── common/OfflineBanner.kt, HapticFeedback.kt, ShimmerLoading.kt
│           ├── auth/LoginScreen.kt, RegisterScreen.kt, AuthViewModel.kt
│           ├── home/HomeScreen.kt, HomeViewModel.kt, MapPickerState.kt
│           ├── trip/
│           │   ├── LocationTracker.kt    ← interface + PollingLocationTracker + RealtimeLocationTracker stub
│           │   ├── ActiveTripViewModel.kt
│           │   ├── ActiveTripScreen.kt   ← map + draggable sheet (route line TODO)
│           │   ├── SearchingScreen.kt, SearchingViewModel.kt
│           │   ├── NegotiationScreen.kt, NegotiationViewModel.kt
│           │   ├── TripCompleteScreen.kt
│           │   └── RateDriverScreen.kt
│           ├── history/TripHistoryScreen.kt
│           └── profile/ProfileScreen.kt
└── DriverAntar/             ← Driver app
    └── app/src/main/java/com/richard_salendah/driverantar/
        ├── (see Section 11)
```

---

## 5. Complete API Reference

All under `/api/v1`. 🔒 = requires `Authorization: Bearer <token>`.

### Driver — `/api/v1/driver/`

```
POST   /register                    public
POST   /login                       public   Returns access_token, refresh_token, driver_id, full_name
POST   /refresh                     public
POST   /fcm-token                   🔒  Call after every login and onNewToken()
GET    /profile                     🔒  Includes avg_rating, rating_count, island_id, island_name
PATCH  /profile                     🔒
POST   /avatar                      🔒  multipart, field "avatar", max 2MB
GET    /vehicle-types               🔒  Enabled types only
POST   /location                    🔒  Updates GPS + auto-stamps island_id, sets is_online=true
POST   /offline                     🔒  Sets is_online=false
POST   /vehicles                    🔒
GET    /vehicles                    🔒
PATCH  /vehicles/:id                🔒
DELETE /vehicles/:id                🔒
POST   /vehicles/:id/set-active     🔒  Required before going online
GET    /earnings                    🔒  Today/week/month/all-time + avg_rating
GET    /earnings/daily              🔒  Last 7 rows, 0 for empty days
GET    /trips/active                🔒  Recovery: offered/agreed/in_progress or null
GET    /trips/incoming              🔒  Open trips matching driver's vehicle type + island
GET    /trips                       🔒  History (completed+cancelled) ?limit=20&offset=0
POST   /trips/:id/offer             🔒  Initial price offer; locks trip; FCM to rider
POST   /trips/:id/counter           🔒  Counter after rider countered (last_offer_by = rider)
POST   /trips/:id/start             🔒  agreed → in_progress
POST   /trips/:id/complete          🔒  in_progress → completed
POST   /trips/:id/cancel            🔒  Cancel when agreed (before start)
POST   /trips/:id/rate              🔒  Rate rider 1-5 stars, one-time only
```

### Rider — `/api/v1/rider/`

```
POST   /register                    public
POST   /login                       public
POST   /refresh                     public
POST   /fcm-token                   🔒
GET    /profile                     🔒  Includes island_id, island_name
PATCH  /profile                     🔒
POST   /avatar                      🔒
GET    /nearby-drivers              🔒  ?lat=X&lng=Y&vehicle_type_id=N (optional filter)
POST   /request-ride                🔒  vehicle_type_id REQUIRED; pg trigger queues FCM
GET    /trips/active                🔒  Recovery: current open trip or null
GET    /trips                       🔒  History ?limit=20&offset=0
GET    /trips/:id                   🔒  Single trip with negotiation state + driver_lat/driver_lng
POST   /trips/:id/accept            🔒  → agreed; FCM to driver
POST   /trips/:id/reject            🔒  → requested, resets all counters
POST   /trips/:id/counter           🔒  (last_offer_by must be driver)
POST   /trips/:id/cancel            🔒  Only while status=requested
POST   /trips/:id/rate              🔒  Rate driver 1-5 stars
```

### Admin — `/api/v1/admin/` (all 🔒)

```
GET/POST   /vehicle-types
PATCH      /vehicle-types/:type_id
GET        /fare-rules
PATCH      /fare-rules/:type_id        Sets default_fare floor (transport AND errand)
GET        /payment-methods
PATCH      /payment-methods/:id        Toggle is_enabled (cash cannot be disabled)
GET        /islands
PATCH      /islands/:island_id         Update search_radius_m
GET        /settings/negotiation
PATCH      /settings/negotiation       max_negotiation_rounds (0 or 1 = disabled)
```

---

## 6. Database Schema (Live on Supabase)

### Key Tables

**driver_profiles**
```
id uuid PK → auth.users
full_name text NOT NULL
phone_number text NOT NULL
email text
avatar_url text
is_online boolean DEFAULT false
last_location geography(Point,4326)
last_lat double precision          ← ✅ ALREADY EXISTS (Option B prep done at DB level)
last_lng double precision          ← ✅ ALREADY EXISTS (Option B prep done at DB level)
active_vehicle_id uuid → driver_vehicles
island_id int → islands
fcm_token text
avg_rating numeric(3,2)
rating_count int DEFAULT 0
created_at, updated_at timestamptz
```

**rider_profiles**
```
id uuid PK → auth.users
full_name, phone_number text NOT NULL
email, avatar_url text
island_id int → islands
fcm_token text
avg_rating numeric(3,2), rating_count int
created_at, updated_at timestamptz
```

**trips**
```
id uuid PK
rider_id, driver_id, offered_by uuid
status trip_status enum
trip_type trip_type enum
vehicle_type_id int NOT NULL
island_id int
pickup_location, dropoff_location geography(Point,4326)
pickup_address text NOT NULL
dropoff_address, note text
fare, offered_fare numeric(10,2)
payment_method_id int
last_offer_by text ('driver'|'rider')
offer_round, driver_counter_count, rider_counter_count int
notified_driver_id uuid, notified_at timestamptz, notification_attempts int
created_at, updated_at timestamptz
```

**Note:** `trips` does NOT have separate `pickup_lat`/`pickup_lng`/`dropoff_lat`/`dropoff_lng` columns. These are extracted via `ST_Y`/`ST_X` in queries. They need to be added to the rider `TripResponse` — see Section 9.

### Enums
- `trip_status`: requested, offered, agreed, in_progress, completed, cancelled
- `trip_type`: transport, errand

### Islands (Talaud GeoJSON boundaries loaded)
| Island | search_radius_m |
|---|---|
| Karakelang | 3000 |
| Kabaruan | 1500 |
| Salibabu | 1000 |
| Sara Besar | 500 |
| Sara Kecil | 300 |

### DB Functions & Triggers
- `resolve_island_id(lng, lat)` — point-in-polygon, returns island id or NULL
- `notify_nearest_driver_on_insert()` — AFTER INSERT on trips; finds nearest matching driver by vehicle type + island + distance; inserts to notification queue
- `process_trip_notification_timeouts()` — pg_cron every minute; 3-min timeout retry; auto-cancel after 5 attempts; filters by vehicle_type_id
- `refresh_avg_rating()` — AFTER INSERT on ratings; updates avg_rating + rating_count on profiles

---

## 7. Key Business Rules

**Trip flow:** requested → offered → agreed → in_progress → completed (cancelled possible at various points)

**Vehicle type matching:** rider picks vehicle_type_id. The pg trigger, pg_cron fallback, and IncomingTrips query ALL filter by matching vehicle type. A bentor driver never sees a car trip.

**Island isolation:** GPS coordinate → ST_Within against boundary polygon → island_id stamped on profiles and trips. All queries filter island_id. Cross-island matching impossible at SQL level.

**Fare floor:** `default_fare` from fare_rules applies to BOTH transport AND errand, for BOTH initial offer AND every counter-offer.

**Negotiation rounds:** max from app_settings (default 6). Rider gets CEIL(max/2) counters, driver gets FLOOR(max/2). Set to 0 or 1 to disable countering entirely.

**Errand trips:** note field required server-side. Pickup is rider's GPS. Dropoff optional.

---

## 8. Environment Variables

### Server (.env)
```
DB_USER, DB_PASSWORD, DB_HOST, DB_PORT, DB_NAME
SUPABASE_URL=https://lbiijuuugqgcfrpksilh.supabase.co
SUPABASE_ANON_KEY=<anon key>
SUPABASE_JWT_SECRET=<jwt secret>
GOOGLE_APPLICATION_CREDENTIALS=/etc/antar/firebase-service-account.json
APP_ENV=production
APP_PORT=8080
```

### Android (local.properties — never commit)
```
sdk.dir=/path/to/sdk
BASE_URL=https://your-server.com/
SUPABASE_URL=https://lbiijuuugqgcfrpksilh.supabase.co
SUPABASE_ANON_KEY=<anon key>
```

---

## 9. PENDING WORK — DO THIS NEXT

### 9.1 Add pickup/dropoff coords to rider TripResponse (server + Android)

**Why:** ActiveTripScreen currently can only show the driver pin because `TripResponse` has no pickup/dropoff coordinates. Needed for pickup pin, dropoff pin, and OSRM routing.

**Server — `rider/handler.go` — add 4 columns to `tripSelect`:**
```go
// After the driver_lat / driver_lng lines, add:
COALESCE(ST_Y(t.pickup_location::geometry),  0) AS pickup_lat,
COALESCE(ST_X(t.pickup_location::geometry),  0) AS pickup_lng,
COALESCE(ST_Y(t.dropoff_location::geometry), 0) AS dropoff_lat,
COALESCE(ST_X(t.dropoff_location::geometry), 0) AS dropoff_lng,
```

**Server — `rider/model.go` — add to `TripResponse`:**
```go
PickupLat  float64 `json:"pickup_lat"`
PickupLng  float64 `json:"pickup_lng"`
DropoffLat float64 `json:"dropoff_lat"`
DropoffLng float64 `json:"dropoff_lng"`
```

**Server — `rider/handler.go` — add to `scanTrip()`:**
```go
&t.PickupLat, &t.PickupLng, &t.DropoffLat, &t.DropoffLng,
```

**Android — `Models.kt` — add to `TripResponse`:**
```kotlin
@SerializedName("pickup_lat")  val pickupLat:  Double = 0.0,
@SerializedName("pickup_lng")  val pickupLng:  Double = 0.0,
@SerializedName("dropoff_lat") val dropoffLat: Double = 0.0,
@SerializedName("dropoff_lng") val dropoffLng: Double = 0.0,
```

### 9.2 Add OSRM road routing to rider ActiveTripScreen

**Why:** Show a road-following line from driver → pickup (when `agreed`) or driver → dropoff (when `in_progress`), just like Google Maps/Grab.

**Routing engine:** OSRM public demo server — free, no API key, uses OpenStreetMap data.
```
GET https://router.project-osrm.org/route/v1/driving/{driverLng},{driverLat};{destLng},{destLat}?overview=full&geometries=geojson
```
Returns a GeoJSON `LineString` of the actual road path.

**Route refresh strategy — don't call OSRM every 3 seconds:**
- Fetch on first location fix
- Re-fetch only when driver has moved >50 metres from last fetch point (use Haversine distance)
- Cache the last route — redraw on every marker update without re-fetching
- Switch destination: driver→pickup when `agreed`, driver→dropoff when `in_progress`

**File to create: `RouteHelper.kt`** (in `ui/trip/`):
```kotlin
object RouteHelper {
    // Fetches OSRM route, returns List<GeoPoint> or null (falls back to straight line)
    suspend fun fetchRoute(originLat, originLng, destLat, destLng): List<GeoPoint>?
    // Haversine distance in metres
    fun distanceMeters(lat1, lng1, lat2, lng2): Double
}
```

**Draw route on OSMDroid:**
```kotlin
val polyline = Polyline(mapView).apply {
    setPoints(routePoints)
    outlinePaint.apply {
        color       = android.graphics.Color.parseColor("#1B6CA8")
        strokeWidth = 8f
        isAntiAlias = true
        strokeCap   = android.graphics.Paint.Cap.ROUND
        strokeJoin  = android.graphics.Paint.Join.ROUND
    }
}
mapView.overlays.add(polyline)
```

**Visual spec for ALL map pins (rider AND driver app must match):**
| Element | Colour | Size |
|---|---|---|
| Pickup pin | `#1B6CA8` PrimaryBlue | 30dp |
| Dropoff pin | `#E53935` Red | 30dp |
| Driver pin | `#03A9F4` AccentBlue | 32dp |
| Rider/user pin | `#1B6CA8` PrimaryBlue | 18dp |
| Route line | `#1B6CA8`, 8px, rounded caps | — |

### 9.3 Update driver app UpdateLocation handler to write last_lat/last_lng

**Why:** `driver_profiles` already has `last_lat` and `last_lng` columns (confirmed in DB). The Go handler needs to write them alongside `last_location` so Option B Realtime can broadcast clean floats.

**Server — `driver/handler.go` — UpdateLocation function:**
```go
result, err := h.db.Exec(context.Background(),
    `UPDATE driver_profiles
     SET last_location = ST_SetSRID(ST_MakePoint($1,$2),4326),
         last_lat      = $2,   ← ADD THESE TWO
         last_lng      = $1,   ← ADD THESE TWO (lng=$1, lat=$2)
         island_id     = resolve_island_id($1, $2),
         updated_at    = $3,
         is_online     = true
     WHERE id = $4`,
    req.Longitude, req.Latitude, time.Now(), driverID,
)
```

### 9.4 Driver app — Add map + routing to ActiveTripScreen

See Section 10 (full driver map guide) below.

---

## 10. Driver App — Map & Routing Implementation Guide

The driver app's ActiveTripScreen currently exists but has no map. It needs to show:
- `agreed` status: driver pin (from GPS) + pickup pin + road line driver→pickup
- `in_progress` status: driver pin + pickup pin + dropoff pin + road line driver→dropoff

**What the driver app already has:**
- `DriverTripResponse` already contains `pickup_lat`, `pickup_lng`, `dropoff_lat`, `dropoff_lng` (these were added in the original build)
- GPS position available from `LocationService.locationFlow` (SharedFlow, emits every 3s)
- OSMDroid already used on the map screen
- `SupabaseClientHolder` for Realtime

**Step 1 — Create `RouteHelper.kt` in `ui/trip/`** (identical to rider app version):
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
            conn.readTimeout = 6_000
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
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
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat/2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng/2).pow(2)
        return r * 2 * atan2(sqrt(a), sqrt(1-a))
    }
}
```

**Step 2 — Update `ActiveTripViewModel.kt`:**

Add route state alongside existing trip state:
```kotlin
var routePoints by mutableStateOf<List<GeoPoint>>(emptyList())
    private set
private var lastRouteFetchLat = 0.0
private var lastRouteFetchLng = 0.0

// Call this when driverLocation updates (observe LocationService.locationFlow):
private fun fetchRouteIfNeeded(driverLat: Double, driverLng: Double, trip: DriverTripResponse) {
    val moved = RouteHelper.distanceMeters(driverLat, driverLng, lastRouteFetchLat, lastRouteFetchLng)
    if (moved < 50.0 && routePoints.isNotEmpty()) return // not moved enough
    
    val (destLat, destLng) = when (trip.status) {
        "agreed"      -> Pair(trip.pickup_lat, trip.pickup_lng)
        "in_progress" -> if (trip.dropoff_lat != 0.0) Pair(trip.dropoff_lat, trip.dropoff_lng)
                         else Pair(trip.pickup_lat, trip.pickup_lng)
        else -> return
    }
    lastRouteFetchLat = driverLat
    lastRouteFetchLng = driverLng
    viewModelScope.launch {
        val pts = RouteHelper.fetchRoute(driverLat, driverLng, destLat, destLng)
        if (pts != null) routePoints = pts
    }
}
```

**Step 3 — Update `ActiveTripScreen.kt`:**

Replace existing content with a full-screen map behind a draggable bottom sheet (same pattern as rider `ActiveTripScreen`):

```kotlin
// Map setup
AndroidView(
    factory = { ctx ->
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
        }.also { mapRef = it }
    },
    modifier = Modifier.fillMaxSize()
)

// Update overlays on every location + route change
LaunchedEffect(driverGeoPoint, trip, routePoints) {
    val map = mapRef ?: return@LaunchedEffect
    map.overlays.removeAll { it is Marker || it is Polyline }
    
    // Route line
    if (routePoints.isNotEmpty()) {
        val lineColor = if (trip?.status == "in_progress")
            android.graphics.Color.parseColor("#E53935")
        else android.graphics.Color.parseColor("#1B6CA8")
        Polyline(map).apply {
            setPoints(routePoints)
            outlinePaint.color = lineColor
            outlinePaint.strokeWidth = 8f
            outlinePaint.isAntiAlias = true
            map.overlays.add(this)
        }
    }
    
    // Pickup pin (blue, always visible)
    trip?.let { t ->
        if (t.pickup_lat != 0.0) {
            Marker(map).apply {
                position = GeoPoint(t.pickup_lat, t.pickup_lng)
                title = "Jemput: ${t.rider_name}"
                icon = circleDrawable(map.context, 0xFF1B6CA8.toInt(), 30)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(this)
            }
        }
        // Dropoff pin (red, only in_progress for transport)
        if (t.status == "in_progress" && t.trip_type == "transport" && t.dropoff_lat != 0.0) {
            Marker(map).apply {
                position = GeoPoint(t.dropoff_lat, t.dropoff_lng)
                title = "Tujuan"
                icon = circleDrawable(map.context, 0xFFE53935.toInt(), 30)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                map.overlays.add(this)
            }
        }
    }
    
    // Driver pin (accent blue, moving)
    if (driverGeoPoint.latitude != 0.0) {
        Marker(map).apply {
            position = driverGeoPoint
            title = "Posisi Anda"
            icon = circleDrawable(map.context, 0xFF03A9F4.toInt(), 32)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            map.overlays.add(this)
        }
    }
    map.invalidate()
}
```

The `circleDrawable()` helper (identical in both apps):
```kotlin
private fun circleDrawable(context: android.content.Context, colorInt: Int, sizeDp: Int)
    : android.graphics.drawable.BitmapDrawable {
    val px = (sizeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(8)
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val cvs = Canvas(bmp)
    cvs.drawCircle(px/2f, px/2f, px/2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorInt })
    cvs.drawCircle(px/2f, px/2f, px/2f - px*0.09f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = px * 0.18f
    })
    return android.graphics.drawable.BitmapDrawable(context.resources, bmp)
}
```

**Bottom sheet for driver ActiveTripScreen:**
Same draggable sheet pattern. Collapsed = trip type pill + fare + rider name + call button. Expanded = pickup/dropoff address + errand note + start/complete/cancel buttons.

---

## 11. Option B Migration Guide (Realtime driver location — do after app is stable)

**Current state:** Option A (polling) is active. `driver_profiles` already has `last_lat` and `last_lng` double precision columns. DB prep is 50% done.

### Remaining steps to enable Option B:

**Step 1 — Server: write last_lat/last_lng in UpdateLocation handler** (Section 9.3 above)

**Step 2 — Supabase SQL (one migration):**
```sql
-- Add driver_profiles to the Realtime publication
ALTER PUBLICATION supabase_realtime ADD TABLE driver_profiles;

-- Allow riders to subscribe to driver location rows
-- (driver_profiles has RLS; without this, rider's Supabase client gets nothing)
CREATE POLICY "riders can read driver location"
ON driver_profiles FOR SELECT
TO authenticated
USING (true);
```

**Step 3 — Android rider app: implement `RealtimeLocationTracker` stub in `LocationTracker.kt`:**
```kotlin
class RealtimeLocationTracker(
    private val api: ApiService,
    private val supabase: SupabaseClient,
    private val driverProfileId: String,
) : LocationTracker {
    private val pollingFallback = PollingLocationTracker(api, intervalMs = 15_000L)
    private val _location = MutableStateFlow<DriverLocation?>(null)
    override val location: StateFlow<DriverLocation?> = _location.asStateFlow()

    override fun start(tripId: String, scope: CoroutineScope) {
        pollingFallback.start(tripId, scope) // fallback at 15s interval
        scope.launch {
            runCatching {
                val ch = supabase.channel("driver-location-$driverProfileId")
                ch.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "driver_profiles"
                    filter("id", FilterOperator.EQ, driverProfileId)
                }.onEach { action ->
                    val lat = action.record["last_lat"]?.jsonPrimitive?.doubleOrNull ?: return@onEach
                    val lng = action.record["last_lng"]?.jsonPrimitive?.doubleOrNull ?: return@onEach
                    _location.value = DriverLocation(lat, lng)
                }.launchIn(this)
                ch.subscribe()
            }
        }
    }
    override fun stop() { pollingFallback.stop() }
}
```

**Step 4 — `ActiveTripViewModel` — change one line:**
```kotlin
// BEFORE
private val locationTracker: LocationTracker = PollingLocationTracker(api, intervalMs = 3_000L)

// AFTER (get driverProfileId from trip.driverId)
private val locationTracker: LocationTracker = RealtimeLocationTracker(api, supabase, driverProfileId)
```

---

## 12. Known Bugs Fixed (do not redo)

| Bug | Fix |
|---|---|
| pgx v5 can't scan `timestamptz` into `string` | Cast all timestamp columns as `::text` in SQL queries |
| BEFORE INSERT trigger had NULL `NEW.id` for FK | Changed to AFTER INSERT trigger; use separate UPDATE statement |
| `ktor-client-android` has no WebSocket support | Changed to `ktor-client-okhttp` in `build.gradle.kts` |
| pgx v5 rejects unused `$N` params | Remove unused params and renumber remaining ones |
| rider/trips/:id returns 500 when `trip` is null in NegotiationScreen | Added `::text` casts to `tripSelect` + fixed null-trip error branch in NegotiationScreen |
| build.gradle.kts `java.util.Properties` unresolved | Add `import java.util.Properties` and `import java.io.FileInputStream` at top of file |

---

## 13. Supabase Realtime Pattern (Kotlin)

Used in `SearchingViewModel`, `NegotiationViewModel`, `ActiveTripViewModel`, `WaitingForRiderViewModel` (driver app).

```kotlin
val channel = supabase.channel("unique-channel-name-$tripId")

channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
    table  = "trips"
    filter("id", FilterOperator.EQ, tripId)
}.onEach { action ->
    val status = action.record["status"]?.jsonPrimitive?.content ?: return@onEach
    when (status) {
        "offered"     -> onOfferReceived()
        "agreed"      -> onOfferAccepted()
        "in_progress" -> { }
        "completed"   -> onTripCompleted()
        "cancelled"   -> onTripCancelled()
        "requested"   -> onTripReset() // rejected, back to searching
    }
}.launchIn(viewModelScope)

channel.subscribe()

// In onCleared():
viewModelScope.launch {
    runCatching { supabase.realtime.removeChannel(channel) }
}
```

**Always pair Realtime with a polling fallback** — WebSocket drops on poor Talaud connectivity.

---

## 14. Rider App — Navigation Flow

```
Login → Home
Register → Login

Home → Searching (after booking)  ← booking lives in HomeScreen bottom sheet
Home (on load) → Searching / Negotiation / ActiveTrip (active trip recovery)

Searching → Negotiation (offer received via Realtime or 5s poll)
Searching → Home (cancelled)

Negotiation → ActiveTrip (accepted)
Negotiation → Searching (rejected/reset)

ActiveTrip → TripComplete → RateDriver → Home
TripComplete → Home (skip)

Home → History (tap)
Home → Profile (tap) → Logout → Login
```

---

## 15. Driver App — Navigation Flow

```
Login → Map
Register → Login

Map (online) → IncomingTrips
IncomingTrips → OfferPrice (tap trip)
OfferPrice → WaitingForRider (offer submitted)
WaitingForRider → ActiveTrip (agreed)
WaitingForRider → CounterDecision (rider countered)
CounterDecision → WaitingForRider (driver countered or matched price)
CounterDecision → IncomingTrips (rejected)

ActiveTrip → RateRider → Map
ActiveTrip → Map (cancelled)

Map → Profile → [AddVehicle, Earnings, TripHistory, Logout]
```

---

## 16. Rider App — Key File Notes

**`Antar.kt` (Application class):**
- Singletons: `sessionManager`, `apiService`, `supabase`
- FCM channel: `antar_rider`
- Foreground tracker: `isForeground` bool for FCM routing

**`HomeScreen.kt` / `HomeViewModel.kt`:**
- Booking is entirely inside the bottom sheet — no separate booking screens
- Two sheet steps: `Main` (form) → `Summary` (confirm)
- `PickerMode` enum: `None`, `Pickup`, `Dropoff` — map tap sets coordinates
- Vehicle chips show per-type online count computed client-side from nearby drivers list
- `countByType: Map<String, Int>` in ViewModel

**`LocationTracker.kt`:**
- Interface + `PollingLocationTracker` (3s, active) + `RealtimeLocationTracker` (stub, delegates to polling)
- `ActiveTripViewModel` depends only on the interface — swap Option B in by changing one line

**`ActiveTripScreen.kt`:**
- Full-screen OSMDroid map + draggable sheet (min 180dp, max 75% screen height, snaps at 40%)
- Collapsed: status stepper + driver bar (always visible)
- Expanded: fare card + route detail card
- Driver pin at `#03A9F4` (AccentBlue), moving every 3s
- **TODO:** pickup/dropoff pins + route line once coords added (Section 9)

**`DeepLinkHandler.kt`:**
- Singleton SharedFlow for FCM tap events
- Events: `ToNegotiation(tripId)`, `ToActiveTrip(tripId)`
- `NavGraph` collects these in a `LaunchedEffect` and navigates

---

## 17. Driver App — Key File Notes

**Package:** `com.richard_salendah.driverantar`  
**Application class:** `DriverApplication`

**`SessionManager`** — `EncryptedSharedPreferences` (not DataStore like rider app)

**`RetrofitClient`** — `AuthInterceptor` auto-refreshes on 401; broadcasts `ACTION_SESSION_EXPIRED` on failure; `MainActivity` listens and navigates to login

**`LocationService`** — foreground service; captures JWT at start for safe `goOffline()` on destroy; emits `locationFlow` SharedFlow; `isRunning` static bool for stale-online detection

**`WaitingForRiderViewModel`** — subscribes to `trips` via Realtime; handles `RiderCountered(riderFare, driverCounterCount)` state; uses timestamp suffix on channel name to avoid reuse conflict

**`ActiveTripViewModel`** (driver) — Realtime subscription on trips table; separate `loadTrip()` for full data refresh; `startTrip()`, `completeTrip()`, `requestCancel()`, `confirmCancel()` actions

**`MapViewModel`** — `recoverActiveTrip()` called on every resume; maps trip status to navigation route; `refreshProfile()` on Lifecycle.ON_RESUME to pick up vehicle changes

---

## 18. What To Tell Claude In A New Session

Paste this file, then say something like:

> "I'm working on Antar, a ride-hailing app for Kepulauan Talaud. Please read the context above. I want to [specific task]."

**Most likely next tasks:**
1. Add pickup/dropoff coords to rider TripResponse (Section 9.1)
2. Create `RouteHelper.kt` and update `ActiveTripScreen.kt` for routing (Section 9.2)
3. Update driver UpdateLocation handler to write last_lat/last_lng (Section 9.3)
4. Implement driver ActiveTripScreen with map + routing (Section 10)
5. When stable: Option B Realtime migration (Section 11)
