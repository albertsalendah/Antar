# Antar — Project Context
**Last updated:** May 2026
**Paste this file at the start of every new session.**

---

## 1. What This Project Is

Antar is a Grab/GoJek-style ride-hailing app for the **Kepulauan Talaud archipelago, North Sulawesi, Indonesia**. Two Android apps (driver + rider) backed by a single Go server.

**Key local adaptations:**
- Island isolation: riders and drivers matched only within the same island via PostGIS polygon boundaries
- Price negotiation: driver offers price, rider can accept / reject / counter-offer (max rounds admin-configurable, currently 6)
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

## 3. Repository Structure

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
│   └── supabase/migrations/ (22 migrations, all applied)
│
├── Antar/                          ← Rider app
│   └── app/src/main/java/com/richard_salendah/antar/
│       ├── Antar.kt                ← Application class, singletons: sessionManager, apiService, supabase
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
│           ├── booking/BookingViewModel.kt, VehicleTypePickerScreen.kt, BookingConfirmScreen.kt
│           ├── trip/
│           │   ├── LocationTracker.kt         ← interface + PollingLocationTracker + RealtimeLocationTracker (stub)
│           │   ├── ActiveTripViewModel.kt, ActiveTripScreen.kt
│           │   ├── SearchingScreen.kt, SearchingViewModel.kt
│           │   ├── NegotiationScreen.kt, NegotiationViewModel.kt
│           │   ├── TripCompleteScreen.kt
│           │   ├── RateDriverScreen.kt
│           │   └── OsrmRouteHelper.kt         ← road routing via OSRM public API
│           ├── history/TripHistoryScreen.kt
│           └── profile/ProfileScreen.kt
│
└── DriverAntar/                    ← Driver app
    └── app/src/main/java/com/richard_salendah/driverantar/
        ├── DriverApplication.kt
        ├── MainActivity.kt         ← handles FCM deep links, session expired broadcast
        ├── data/
        │   ├── model/Models.kt
        │   └── remote/DriverApiService.kt, DriverRepository.kt, RetrofitClient.kt
        ├── utils/SessionManager.kt (EncryptedSharedPreferences), ConnectivityObserver.kt
        └── ui/
            ├── auth/LoginScreen.kt, RegisterScreen.kt, AuthViewModel.kt
            ├── map/MapScreen.kt, MapViewModel.kt
            ├── profile/ProfileScreen.kt, ProfileViewModel.kt
            ├── vehicle/AddVehicleScreen.kt, VehicleViewModel.kt
            ├── earnings/EarningsScreen.kt, EarningsViewModel.kt
            ├── navigation/Screen.kt, AppNavGraph.kt
            ├── service/LocationService.kt, AntarDriverMessagingService.kt
            ├── supabase/SupabaseClientHolder.kt
            ├── components/OfflineBanner.kt, RatingBar.kt, SkeletonBox.kt
            └── trip/
                ├── IncomingTripsScreen.kt, IncomingTripsViewModel.kt
                ├── OfferPriceScreen.kt, OfferPriceViewModel.kt
                ├── WaitingForRiderScreen.kt, WaitingForRiderViewModel.kt
                ├── CounterDecisionScreen.kt, CounterDecisionViewModel.kt
                ├── ActiveTripScreen.kt, ActiveTripViewModel.kt
                ├── RateRiderScreen.kt, RateRiderViewModel.kt
                ├── TripHistoryScreen.kt, TripHistoryViewModel.kt
                └── TripSelectionHolder.kt
```

---

## 4. Database Schema

### driver_profiles
```
id uuid PK → auth.users
full_name, phone_number text NOT NULL
email, avatar_url text
is_online boolean DEFAULT false
last_location geography(Point,4326)
last_lat double precision          ← exists, ready for Option B Realtime
last_lng double precision          ← exists, ready for Option B Realtime
active_vehicle_id uuid → driver_vehicles
island_id int → islands
fcm_token text
avg_rating numeric(3,2), rating_count int DEFAULT 0
created_at, updated_at timestamptz
```

### rider_profiles
```
id uuid PK → auth.users
full_name, phone_number text NOT NULL
email, avatar_url text
island_id int → islands
fcm_token text
avg_rating numeric(3,2), rating_count int DEFAULT 0
created_at, updated_at timestamptz
```

### trips
```
id uuid PK
rider_id, driver_id, offered_by uuid
status trip_status enum (requested, offered, agreed, in_progress, completed, cancelled)
trip_type trip_type enum (transport, errand)
vehicle_type_id int NOT NULL
island_id int
pickup_location, dropoff_location geography(Point,4326)
pickup_address text NOT NULL, dropoff_address text, note text
fare, offered_fare numeric(10,2)
payment_method_id int DEFAULT 1
last_offer_by text ('driver'|'rider')
offer_round, driver_counter_count, rider_counter_count int DEFAULT 0
notified_driver_id uuid, notified_at timestamptz, notification_attempts int DEFAULT 0
created_at, updated_at timestamptz
```

### Other tables
- `driver_vehicles` — driver's registered vehicles (vehicle_type_id, license_plate, make, model, year, color, is_active)
- `vehicle_types` — Car (disabled), Motorbike (enabled), Bentor (enabled)
- `islands` — Karakelang 3000m, Kabaruan 1500m, Salibabu 1000m, Sara Besar 500m, Sara Kecil 300m
- `fare_rules` — default_fare floor per vehicle type (applies to ALL trip types and ALL counter rounds)
- `payment_methods` — cash (enabled), bank_transfer (disabled), ewallet (disabled)
- `ratings` — trip_id, rater_id, ratee_id, rater_role (driver|rider), score 1-5, comment
- `app_settings` — key/value; `max_negotiation_rounds = 6`
- `driver_notification_queue` — pg_cron populates, Go processor drains via FCM

### DB Functions & Triggers
- `resolve_island_id(lng, lat)` — point-in-polygon, returns island id or NULL
- `notify_nearest_driver_on_insert()` — BEFORE INSERT on trips; finds nearest driver by vehicle type + island + distance; inserts to notification queue
- `process_trip_notification_timeouts()` — pg_cron every minute; 3-min timeout retry; auto-cancel after 5 attempts; filters by vehicle_type_id
- `refresh_avg_rating()` — AFTER INSERT on ratings; updates avg_rating + rating_count on profiles

---

## 5. Complete API Reference

All under `/api/v1`. 🔒 = requires `Authorization: Bearer <token>`.

### Driver `/api/v1/driver/`
```
POST   /register                    public
POST   /login                       public   → access_token, refresh_token, driver_id, full_name
POST   /refresh                     public
POST   /fcm-token                   🔒  Call after every login + onNewToken()
GET    /profile                     🔒  Includes avg_rating, rating_count, island_id, island_name
PATCH  /profile                     🔒
POST   /avatar                      🔒  multipart field "avatar", max 2MB
GET    /vehicle-types               🔒  Enabled types only
POST   /vehicles                    🔒
GET    /vehicles                    🔒
PATCH  /vehicles/:id                🔒
DELETE /vehicles/:id                🔒
POST   /vehicles/:id/set-active     🔒  Required before going online
POST   /location                    🔒  Updates GPS + island_id, sets is_online=true
POST   /offline                     🔒  Sets is_online=false
GET    /earnings                    🔒  Today/week/month/all-time totals + avg_rating
GET    /earnings/daily              🔒  Last 7 rows, 0 for empty days
GET    /trips/active                🔒  Returns offered/agreed/in_progress trip or null
GET    /trips/incoming              🔒  Open trips matching driver's vehicle type + island
GET    /trips                       🔒  History (completed+cancelled) ?limit=20&offset=0
POST   /trips/:id/offer             🔒  Initial price offer; atomic lock; FCM to rider
POST   /trips/:id/counter           🔒  Counter after rider countered (last_offer_by = rider)
POST   /trips/:id/start             🔒  agreed → in_progress
POST   /trips/:id/complete          🔒  in_progress → completed
POST   /trips/:id/cancel            🔒  Cancel when agreed (before start)
POST   /trips/:id/rate              🔒  Rate rider 1-5 stars, one-time only
```

### Rider `/api/v1/rider/`
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
GET    /trips/active                🔒  Current open trip or null (for recovery)
GET    /trips                       🔒  History ?limit=20&offset=0
GET    /trips/:id                   🔒  Full trip detail including driver_lat/lng, pickup/dropoff coords
POST   /trips/:id/accept            🔒  → agreed; FCM to driver
POST   /trips/:id/reject            🔒  → requested, resets all counters
POST   /trips/:id/counter           🔒  last_offer_by must be 'driver'
POST   /trips/:id/cancel            🔒  Only while status=requested
POST   /trips/:id/rate              🔒  Rate driver 1-5 stars
```

### Admin `/api/v1/admin/` (all 🔒)
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

## 6. Key Business Rules

- **Trip flow:** requested → offered → agreed → in_progress → completed (cancelled possible at various points)
- **Vehicle type matching:** rider picks vehicle_type_id; pg trigger, pg_cron, and IncomingTrips ALL filter by it
- **Island isolation:** GPS → ST_Within polygon → island_id stamped on profiles and trips; cross-island matching impossible at SQL level
- **Fare floor:** `default_fare` from fare_rules applies to ALL trip types, for BOTH initial offer AND every counter-offer round
- **Negotiation:** max 6 rounds (admin-configurable). Rider gets CEIL(max/2) counters, driver gets FLOOR(max/2). 0 or 1 = countering disabled
- **Errand trips:** note field required. Pickup is rider's GPS. No dropoff required
- **Active vehicle:** driver must set an active vehicle before going online. Only one active at a time, server tracks via `driver_profiles.active_vehicle_id`

---

## 7. Environment Variables

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

## 8. Navigation Flows

### Rider App
```
Login → Home
Register → Login (or Home if no email confirm needed)

Home → Searching (after booking from bottom sheet)
Home (on load) → Searching / Negotiation / ActiveTrip (active trip recovery)

Searching → Negotiation (offer received via Realtime or 5s poll)
Searching → Home (cancelled)

Negotiation → ActiveTrip (accepted)
Negotiation → Searching (rejected/reset)

ActiveTrip → TripComplete → RateDriver → Home
TripComplete → Home (skip rating)

Home → History → RateDriver (from history card)
Home → Profile → Logout → Login
```

### Driver App
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

## 9. Key Implementation Notes

### Rider — SessionManager
Uses DataStore (not SharedPreferences). Token stored as plain string, attached to requests via `AuthInterceptor` in `ApiClient.kt`. On 401, `TokenAuthenticator` silently refreshes once using `/rider/refresh`.

### Driver — SessionManager
Uses `EncryptedSharedPreferences` (AES256). `RetrofitClient` has `AuthInterceptor` that auto-refreshes on 401 and broadcasts `ACTION_SESSION_EXPIRED` on failure. `MainActivity` listens and navigates to login.

### Driver — LocationService
Foreground service. Captures JWT at start (`capturedToken`) so `goOffline()` still works after `SessionManager.clear()` on logout. Emits `locationFlow` SharedFlow. `isRunning` static bool for stale-online detection in `MapViewModel`.

### Supabase Realtime Pattern (used in both apps)
```kotlin
val channel = supabase.channel("unique-name-$tripId-${System.currentTimeMillis()}")
channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
    table = "trips"
    filter("id", FilterOperator.EQ, tripId)
}.onEach { action ->
    val status = action.record["status"]?.jsonPrimitive?.content ?: return@onEach
    // handle status change
}.launchIn(viewModelScope)
channel.subscribe()
// In onCleared(): supabase.realtime.removeChannel(channel)
```
**Always pair with a polling fallback** — WebSocket drops on poor Talaud connectivity.
Use timestamp suffix on channel name to avoid reuse conflicts when navigating back.

### Driver Location Tracking (Rider App)
`LocationTracker` interface in `ui/trip/LocationTracker.kt`.
- `PollingLocationTracker` — active, polls `GET /rider/trips/:id` every 3s, reads `driver_lat`/`driver_lng`
- `RealtimeLocationTracker` — stub, currently delegates to polling. Ready for Option B migration.
`ActiveTripViewModel` depends only on the interface — swap Option B by changing one line.

### OSRM Road Routing
`OsrmRouteHelper.kt` in rider app `ui/trip/`. Calls public OSRM demo server (no API key).
```
GET https://router.project-osrm.org/route/v1/driving/{lng1},{lat1};{lng2},{lat2}?overview=full&geometries=geojson
```
Re-fetch only when driver has moved >50m from last fetch point (Haversine threshold). Returns `List<GeoPoint>` or null.

### DeepLinkHandler (Rider App)
Singleton `SharedFlow` bridging FCM taps → NavGraph navigation.
Events: `ToNegotiation(tripId)`, `ToActiveTrip(tripId)`.

### HomeScreen Booking (Rider App)
Booking lives entirely in the `HomeScreen` bottom sheet — no separate booking screens in the main flow. Two sheet steps: `Main` (form) → `Summary` (confirm). `PickerMode` enum: `None`, `Pickup`, `Dropoff` — map tap sets coordinates directly.

### Map Pin Colors (consistent across both apps)
| Element | Color | Size |
|---|---|---|
| Pickup pin | `#1B6CA8` PrimaryBlue | 30dp |
| Dropoff pin | `#E53935` Red | 30dp |
| Driver pin | `#03A9F4` AccentBlue | 32dp |
| User/rider pin | `#1B6CA8` PrimaryBlue | 18dp |
| Route line | `#1B6CA8`, 8px, rounded caps | — |
