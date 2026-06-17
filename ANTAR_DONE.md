# Antar — Completed Work Log
**Last updated:** June 2026 (rider app pass + driver bug fixes session)
**Do NOT paste this in new AI sessions unless debugging a regression.**
**Reference only — tracks what has been built and fixed.**

---

## This Session — Rider App Pass + Driver Bug Fixes

### Rider App — route/marker pass (mirrors driver pattern)
- **TOKEN-RACE fixed** — `Apiclient.kt` `TokenAuthenticator` replaced
  `@Volatile isRefreshing` with `Mutex` + `withLock`; concurrent 401s now wait
  on the lock instead of one failing immediately. Token-before-lock comparison
  skips a redundant refresh if another coroutine already completed one.
- **Teardrop pins** — `pinDrawable()` added to `Homescreen.kt` and
  `Activetripscreen.kt`; pickup/dropoff markers switched from `circleDrawable`
  to `pinDrawable`. Driver and user-location markers unchanged (`circleDrawable`).
- **Route caching redesign** — `Activetripviewmodel.kt`: cache state
  (`cachedDestLat/Lng`, `lastOsrmFailureMs`, 5-min `OSRM_COOLDOWN_MS`) moved
  entirely into the ViewModel. Destination-binding means a leg transition
  (agreed→in_progress, pickup→dropoff) automatically triggers a refetch via
  cache-mismatch — no manual reset needed in the Realtime handler.
- **Distance/ETA card** — `Osrmroutehelper.kt` `fetchRoute()` now returns
  `RouteResult(points, distanceMeters, durationSeconds)` parsed from OSRM's
  top-level fields; `fetchRouteWithFallback` removed (fallback logic now in
  the ViewModel, matching driver's `RouteHelper` design exactly). Straight-line
  fallback omits duration. Top-left info card added to `Activetripscreen.kt`,
  hidden on `arrived`.

### Driver App — bug fixes
- **NEGOT-RECOVER fixed** — `MapViewModel.recoverActiveTrip()` previously
  routed `status=offered, last_offer_by=rider` recovery to `IncomingTrips`
  instead of `CounterDecisionScreen`. Now correctly reconstructs the
  `CounterDecision` route with the real `driver_counter_count` from the
  recovered trip (`defaultFare=0.0` is safe — server re-validates the floor
  on every submit).
- **Badge on FAB (in progress)** — `MapViewModel` now polls
  `getIncomingTrips()` every 15s while online and exposes
  `incomingTripCount`; `MapScreen.kt` needs `BadgedBox` applied around the
  "Lihat Perjalanan" FAB (see ANTAR_TODO.md verification list).
- **FCM cold-start navigation (in progress)** — root cause identified:
  `LaunchedEffect(deepLinkRoute)` raced ahead of NavHost's first destination
  on true cold start. Fix designed — route cold start through
  `DeepLinkHandler.emit()` + wait on `currentBackStackEntryFlow` before
  navigating in `AppNavGraph` — pending confirmation of application across
  `DeepLinkHandler.kt`, `AppNavGraph.kt`, `MainActivity.kt`.

### Low-priority fixes
- **LOCALE-DATE-PARSE fixed** — driver `Earningsscreen.kt` `dayAbbrev()` and
  `dayFullName()` switched from `Locale.getDefault()` to `Locale.US` for ISO
  date parsing, avoiding silent NPE → empty chart labels on Eastern Arabic
  numeral locales.
- **DROPOFF-GUARD fixed** — driver `ActiveTripViewModel.canComplete` no
  longer treats `dropoff_lat == 0.0` as an automatic pass on transport trips.
  `RouteHelper.distanceMeters` already returns `Double.MAX_VALUE` for a 0,0
  destination, so the 150m check now safely blocks completion instead of
  silently allowing it.

---

## Migrations Applied (Supabase — all applied)

| Migration | Description |
|---|---|
| 001_initial_schema | driver_profiles, rider_profiles with RLS |
| 002_vehicle_types_and_driver_vehicles | vehicle_types catalogue, driver_vehicles table, active_vehicle_id |
| 003_rider_profiles_and_trips | trips table, trip_status enum |
| 004_islands_and_island_isolation | islands table, island_id on profiles+trips, resolve_island_id() function |
| 005_seed_talaud_island_boundaries | Real GeoJSON polygon boundaries for 5 Talaud islands |
| 006–012 | Profile improvements, fare_rules, vehicle type matching on trips |
| 013_phase2_trip_negotiation_fare_payment | offered/agreed status, trip_type enum, payment_methods, fare_rules redesign |
| 014_fcm_notification_system | FCM tokens on profiles, driver_notification_queue, pg_cron trigger, island search_radius_m |
| 015_ratings_and_earnings | ratings table, avg_rating/rating_count on profiles, refresh_avg_rating trigger |
| 016_vehicle_type_required_and_negotiation | app_settings, vehicle_type_id NOT NULL on trips, offer_round/counter tracking columns |
| 017_fix_rider_profiles_columns | updated_at on rider_profiles, NOT NULL constraints |
| 018+ | Various RLS fixes, driver_profiles last_lat/last_lng columns added |
| fix_rls_islands_and_notification_queue | RLS enabled on islands and driver_notification_queue |
| add_arrived_trip_status | Added `arrived` value to trip_status enum |
| rider_read_driver_profiles_realtime | driver_profiles added to Realtime publication; RLS SELECT policy for authenticated riders |
| security_rls_hardening | SEC-1 to SEC-4: service_role scoping, driver_profiles/rider_profiles OR EXISTS removed, trips driver-select scoped by island+vehicle+online, ratings scoped to rater/ratee |
| expire_stale_offered_trips | NEG-TIMEOUT: process_trip_notification_timeouts() now resets offered trips back to requested after 10 min inactivity |
| fix_missing_notified_driver_index_and_function_search_paths | idx_trips_notified_driver_id created; search_path fixed on refresh_avg_rating, notify_nearest_driver_on_insert, process_trip_notification_timeouts, resolve_island_id |
| fix_remaining_function_search_paths | search_path fixed on sync_driver_lat_lng, notify_nearest_driver_on_insert (final pass) |

---

## Server — Completed Features

### Auth
- Driver + Rider register / login / refresh token via Supabase Auth
- Email confirmation flow (NeedsConfirmation flag)
- JWT middleware supports both ES256 (new Supabase signing keys) and HS256 (legacy secret)
- JWKS cache with 1-hour TTL, no external libs — pure Go crypto/ecdsa

### Driver Endpoints
- Profile CRUD + avatar upload to Supabase Storage (RLS via user JWT)
- Vehicle management (add, list, update, delete, set-active)
- Location update: writes PostGIS point + last_lat/last_lng + island_id + sets is_online=true
- Offline endpoint
- FCM token save
- Vehicle types (enabled only)
- Earnings: today/week/month/all-time + 7-day daily breakdown (generate_series for zero-fill)
- Incoming trips: filtered by vehicle_type_id + island_id + status=requested
- Offer price: atomic lock (UPDATE WHERE status=requested), floor price enforced, FCM to rider
- Counter offer: floor enforced, driver counter limit enforced, FCM to rider
- Start/Complete/Cancel trip
- Rate rider (1-5 stars, one-time per trip, unique constraint)
- Active trip recovery endpoint

### Rider Endpoints
- Profile CRUD + avatar upload
- Nearby drivers: PostGIS ST_DWithin, filtered by island + optional vehicle type
- Request ride: validates coords, vehicle type, payment method, island detection, inserts trip (pg trigger fires FCM)
- Accept offer → agreed + FCM to driver
- Reject offer → back to requested, resets all negotiation counters
- Counter offer: floor enforced, rider counter limit enforced, FCM to driver
- Cancel trip (requested status only)
- Active trip + trip list + single trip (all with driver lat/lng + pickup/dropoff coords via ST_Y/ST_X join)
- Rate driver (1-5 stars)

### Admin Endpoints
- Vehicle types CRUD
- Fare rules update (default_fare floor)
- Payment methods toggle (cash always enabled)
- Islands list + update search_radius_m
- Negotiation settings get/update (max_negotiation_rounds)

### Infrastructure
- FCM HTTP v1 via Google OAuth2 service account
- Notification processor (goroutine, drains driver_notification_queue every 15s)
- pg_cron fallback: 3-min timeout retry, auto-cancel after 5 attempts, vehicle type filter
- Dockerfile (multi-stage, Alpine)
- Graceful shutdown via signal context

### Security — Server (SEC-7, SEC-8)
- **SEC-7**: `IncomingTrips` and `NearbyDrivers` use positional `$N` parameters
- **SEC-8**: Rate limiting via `ulule/limiter/v3` — POST /login (5/min), POST /offer + /request-ride (10/min)

---

## Rider Android App — Completed Screens

| Screen | Notes |
|---|---|
| Login | Blue header branding, inline error |
| Register | Email confirm dialog, password mismatch validation, phone format validation (08/+62) |
| Home | OSMDroid full-screen map, vehicle type chips with online count, trip type toggle, pickup/dropoff pin-tap with teardrop markers, 2-step bottom sheet, nearby driver polling every 5s, active trip recovery on load |
| Searching | Radar animation, Realtime + 5s polling fallback, connection error surfaced after 2 failures, cancel with confirm dialog |
| Negotiation | Fare display, round counter, +/- stepper, accept/counter/reject with confirm dialog, Realtime + 5s polling, counter exhausted message, double-tap guard |
| ActiveTrip | Full-screen map, draggable bottom sheet, driver pin via Realtime+polling, teardrop pickup/dropoff markers, OSRM road route with straight-line fallback and destination-binding cache, distance/ETA top-left info card, 4-step status stepper, status message cards, call button, offline banner, connection-lost warning, stale-hint after 30s |
| TripComplete | Trip summary card, fare display, rate prompt |
| RateDriver | 5-star selector, optional comment, haptic on tap |
| TripHistory | Paginated, shimmer skeleton, pull-to-refresh, infinite scroll, rate button with in-flight state (no double-rate), `last_rated_trip` saved state signal on pop |
| Profile | Avatar upload, inline edit, island badge, logout confirm |
| OfflineBanner | Animated, ConnectivityManager NetworkCallback |

### Rider — Other Completed Work
- `DeepLinkHandler` buffer increased to 4 (DEEP-1 fixed)
- `TokenAuthenticator` in OkHttp: Mutex-based silent 401 refresh (TOKEN-RACE fixed this session), clears session on failure
- DataStore for token storage
- FCM service: foreground routes via DeepLinkHandler, background posts system notification
- Haptic feedback: Tick / Confirm / Error — VIBRATE permission added to manifest
- Shimmer loading: TripCardSkeleton, ProfileSkeleton
- Theme: full Material3 light color scheme
- `RealtimeLocationTracker`: Supabase Realtime primary + 5s polling fallback (reduced from 15s)
- `OsrmRouteHelper`: OkHttp-based, explicit SSLContext (bypasses Firebase SSL context pollution), `RouteResult(points, distanceMeters, durationSeconds)` return type (this session), OSRM failure cooldown moved into ViewModel
- `ActiveTripViewModel`: route caching/cooldown/destination-binding redesign moved entirely into the ViewModel this session (`cachedDestLat/Lng`, `lastOsrmFailureMs`, `OSRM_COOLDOWN_MS`); exposes `routeDistanceMeters`/`routeDurationSeconds`
- Conscrypt added (`org.conscrypt:conscrypt-android:2.5.2`) + `Security.insertProviderAt` in Application `onCreate()` — fixes TLSv1.3/TLSv1.2 on Android 9
- `NavGraph.kt` History→RateDriver: `observeForever` leak fixed → `StateFlow` + `LaunchedEffect` with `last_rated_trip` key

---

## Driver Android App — Completed Screens

| Screen | Notes |
|---|---|
| Login | Email/password, error card |
| Register | Full name/email/phone/password/confirm, email confirm dialog, phone format validation (08/+62) |
| Map | OSMDroid, online/offline toggle, recenter FAB, rating chip, avatar in top bar, active trip recovery on resume, "Lihat Perjalanan" FAB badge (incoming trip count, this session — pending MapScreen.kt confirmation) |
| Profile | Avatar upload, vehicle list, set-active, delete confirm, earnings + history shortcuts, rating bar, island badge |
| AddVehicle | Vehicle type dropdown, license plate, make/model/year/color |
| Earnings | Summary cards, 7-day bar chart (Canvas), rating card, pull-to-refresh, `Locale.US` date parsing (LOCALE-DATE-PARSE fixed this session) |
| IncomingTrips | Poll every 5s, skeleton loading, offline banner, online-check before offer (LocationService.isRunning guard) |
| OfferPrice | Fare stepper, floor price enforcement, trip summary card |
| WaitingForRider | Realtime + 5s polling fallback, spinning clock, offered fare shown |
| CounterDecision | Accept/Counter/Reject, fare stepper, driver counter limit, floor enforcement |
| ActiveTrip | Full-screen map, draggable bottom sheet, OSRM road route with straight-line fallback, teardrop pickup/dropoff markers, distance/ETA info card (top-left, hidden on `arrived`), 4-step stepper, rider info + call, fare card, Realtime + 6s polling fallback (CONN-8), double-tap guard on all actions (CONN-7), complete button within 150m of dropoff (DROPOFF-GUARD hardened this session) |
| RateRider | 5-star tap selector, optional comment, skip option |
| TripHistory | Paginated, skeleton, infinite scroll, date formatted with Locale.US parser |

### Driver — Other Completed Work
- `LocationService`: foreground service, `goOffline()` with 3s timeout (ANR-1 fixed), `SessionManager.init()` guard in `onStartCommand` (INIT-RACE fixed)
- `AuthInterceptor`: Mutex-based token refresh (TOKEN-RACE fixed), `ACTION_SESSION_EXPIRED` broadcast
- `SessionManager`: EncryptedSharedPreferences (AES256-GCM); `init()` recovers from invalidated Keystore master key (KEYSTORE-CRASH fixed)
- `ConnectivityObserver`: `onLost` sets false unconditionally (CONN-9 fixed)
- `TripSelectionHolder`: cleared on composable dispose via `DisposableEffect` (HOLDER-1 fixed)
- `WaitingForRiderViewModel`: Realtime + 5s polling fallback (CONN-6 fixed)
- `RouteHelper`: OSRM failure caching (5-min cooldown), straight-line fallback; `fetchRoute()` returns `RouteResult(points, distanceMeters, durationSeconds)` — feeds the ActiveTrip distance/ETA card
- `ActiveTripViewModel`: route caching/cooldown/destination-binding redesign (`cachedDestLat/Lng`, `lastOsrmFailureMs`, `OSRM_COOLDOWN_MS`); `canComplete` no longer treats `dropoff_lat==0.0` as an automatic pass (DROPOFF-GUARD, this session)
- `ActiveTripScreen`: top-left distance/ETA info card
- `DeepLinkHandler` (driver): FCM taps navigate while app is backgrounded-but-not-killed; cold-start fix designed this session, pending confirmation of application (see ANTAR_TODO.md)
- `MapViewModel.recoverActiveTrip()`: NEGOT-RECOVER fixed this session — rider-countered trips now recover to CounterDecisionScreen instead of IncomingTrips; `incomingTripCount` polling added for FAB badge
- Haptic feedback on offer submit, complete, etc. — VIBRATE permission added to manifest
- Conscrypt added + `Security.insertProviderAt` in Application-level `onCreate()` — fixes TLS on Android 9 (CONSCRYPT-PLACEMENT)
- `TripHistoryScreen`: `formatDate` uses `Locale.US` for parsing (DATE-1 fixed)

---

## Completed TODOs

| Item | What was done |
|---|---|
| TODO-1 | Pickup/dropoff coords already present |
| TODO-2 | Driver `UpdateLocation` SQL writes `last_lat`/`last_lng` |
| TODO-3 | Driver `ActiveTripScreen` full-screen map + draggable sheet + OSRM routing |
| TODO-4A/B/C | Supabase Realtime for driver location in rider ActiveTrip — migration, `RealtimeLocationTracker`, `ActiveTripViewModel` |
| TODO-5 | `arrived` trip status — DB migration, server endpoint, FCM, both app screens |
| TODO-6 | Rider negotiation +/- stepper matching driver UX |
| TODO-7 | OSRM straight-line fallback — now lives in each ViewModel (both apps) rather than a separate helper function |

---

## Security — Completed (DB + Server)

| ID | What was done |
|---|---|
| SEC-1 | All "service all" policies scoped to `service_role` |
| SEC-2 | Removed broken `OR EXISTS(auth.users)` fallback from profile policies |
| SEC-3 | `trips: driver select relevant` scoped by island+vehicle+online |
| SEC-4 | `ratings` SELECT scoped to authenticated rater/ratee only |
| SEC-7 | `IncomingTrips` and `NearbyDrivers` use positional `$N` parameters |
| SEC-8 | Rate limiting on login and offer endpoints |
| NEG-TIMEOUT | Stale `offered` trips auto-reset to `requested` after 10 min |
| search_path | All 5 app-owned DB functions hardened with `SET search_path = public, pg_catalog` |

---

## Bug Fixes — Full History

| Fix | Issue | Solution |
|---|---|---|
| NavGraph observeForever leak | `observeForever` in History composable never unsubscribed | Replaced with `savedStateHandle.getStateFlow("last_rated_trip")` + `LaunchedEffect` |
| Route line not rendering (rider) | `routePoints` missing from `LaunchedEffect` keys in `ActiveTripScreen` | Added `routePoints` as key; map overlays now redraw when route updates |
| Route line race condition (rider) | `trip` was null when first `driverLocation` emit arrived in `observeLocationForRouting` | Replaced separate collect with `combine(_driverLocation, _trip)` |
| OSRM "Handshake failed" (rider) | Firebase 34.x pollutes global `SSLContext.getDefault()` | Explicit `SSLContext` + system `TrustManager` in `OsrmRouteHelper` OkHttpClient |
| TLSv1.3 on Android 9 | Android 9 only supports TLSv1.2; OSRM requires TLSv1.3 | Added Conscrypt provider in both app Application `onCreate()` (CONSCRYPT-PLACEMENT) |
| Haptic crash on Android 9 | `VIBRATE` permission missing from manifest | Added to both `AndroidManifest.xml` |
| Slow driver movement (rider) | `PollingLocationTracker` fallback was 15s | Reduced to 5s — worst-case lag now ~8s instead of ~18s |
| OSRM wasted retries | Every driver movement triggered a ~1s failing OSRM call | 5-min failure cooldown, now tracked per-ViewModel in both apps |
| DEEP-1 | `DeepLinkHandler` buffer=1 dropped second simultaneous FCM event (rider) | Increased to `extraBufferCapacity = 4` |
| DB missing index | `trips.notified_driver_id` FK had no index | Created `idx_trips_notified_driver_id` via migration |
| E | `getDailyEarnings` 404 — URL missing `api/v1/` prefix | Fixed `@GET` path in `DriverApiService.kt` |
| 1 | Rider ActiveTrip route line never shows (first fix) | `observeLocationForRouting` returned early when trip was null on first emit |
| 2 | No feedback that driver is on the way | Added contextual status message cards below stepper |
| 4 | Complete button visible immediately on trip start | `canComplete` only enabled within 150m of dropoff |
| 5 | Rider marker stays at pickup when in_progress | Suppress separate rider marker; single combined marker at driver position |
| A | Counter button visible when rounds exhausted | `counterExhausted` state hides button and shows message |
| B | HomeScreen geocode bypass when coords are zero | Geocodes pickup address if coords still 0.0 before submitting |
| C | Route line never shows (second root cause) | Added `mapView` as `LaunchedEffect` key in rider `ActiveTripScreen` |
| D | Pickup pin visible when rider is in vehicle | Hidden when `status == "in_progress"` on both screens |
| EARN-TZ | Daily earnings chart used wrong timezone | `GetDailyEarnings` uses `AT TIME ZONE 'Asia/Makassar'` |
| REQ-DUP-SRV | `RequestRide` no duplicate guard | `WHERE NOT EXISTS` check before INSERT |
| CONSCRYPT-PLACEMENT | Conscrypt provider registered too late (Activity, not Application) | Moved `Security.insertProviderAt` to Application-level `onCreate()`, both apps |
| ROUTE-FALLBACK-MISSING / OSRM-CACHE-MISSING (driver) | Route cache/cooldown/destination-binding logic was scattered | Redesigned entirely inside `Activetripviewmodel.kt` |
| DIST-ETA (driver) | No distance/time-to-destination shown on ActiveTrip | `RouteHelper.fetchRoute()` returns `RouteResult`; top-left info card |
| NOTIF-DEEPLINK (driver) | FCM taps ignored while app backgrounded-but-not-killed | New `DeepLinkHandler` (driver); `MainActivity.onNewIntent()` emits; `AppNavGraph` collects |
| KEYSTORE-CRASH (driver) | `EncryptedSharedPreferences.create()` crash-loops on cold start if Keystore key invalidated | `SessionManager.init()` wraps in try/catch, recreates with fresh key |
| TOKEN-RACE (rider) | `@Volatile isRefreshing` let two simultaneous 401s race past the guard | Replaced with `Mutex` + `withLock`, token-before-lock comparison avoids double refresh |
| ROUTE-FALLBACK-MISSING / OSRM-CACHE-MISSING (rider) | Rider lacked the driver's route caching/cooldown/destination-binding design | Ported into `Activetripviewmodel.kt`; `fetchRouteWithFallback` removed, logic now in ViewModel |
| DIST-ETA (rider) | No distance/time-to-destination shown on ActiveTrip | `OsrmRouteHelper.fetchRoute()` returns `RouteResult`; top-left info card on rider `Activetripscreen.kt` |
| LOCALE-DATE-PARSE (driver) | `Locale.getDefault()` could NPE-silently on Eastern Arabic numeral locales, breaking earnings chart day labels | `dayAbbrev()`/`dayFullName()` switched to `Locale.US` |
| DROPOFF-GUARD (driver) | `canComplete` returned `true` when `dropoff_lat == 0.0` on transport trips, bypassing the 150m proximity check | Removed the explicit `|| dropoff_lat == 0.0` escape; `distanceMeters` already returns `MAX_VALUE` for 0,0 so the check fails safe |
| NEGOT-RECOVER (driver) | `recoverActiveTrip()` sent rider-countered trips to `IncomingTrips` instead of `CounterDecisionScreen`, losing negotiation state on app kill | Reconstructs the `CounterDecision` route with the trip's real `driver_counter_count` |

---

## Database — Current Live State (June 2026)

- **Vehicle types:** Car (disabled), Motorbike (enabled), Bentor (enabled)
- **Islands:** Karakelang 3000m, Kabaruan 1500m, Salibabu 1000m, Sara Besar 500m, Sara Kecil 300m
- **Payment methods:** Cash (enabled), Bank Transfer (disabled), E-Wallet (disabled)
- **Negotiation setting:** max_negotiation_rounds = 6 (rider gets 3, driver gets 3)
- **All migrations applied**, no pending migrations
- **RLS enabled** on all application tables
- **last_lat / last_lng** populated on every location update
- **Offered trip expiry:** stale `offered` trips auto-reset after 10 min via pg_cron
- **All 5 app-owned functions** have `SET search_path = public, pg_catalog`
- **idx_trips_notified_driver_id** index exists
