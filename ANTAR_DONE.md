# Antar — Completed Work Log
**Last updated:** May 2026
**Do NOT paste this in new AI sessions unless debugging a regression.**
**Reference only — tracks what has been built and fixed.**

---

## Migrations Applied (Supabase — all 22 applied)

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
- Location update: writes PostGIS point + island_id via resolve_island_id() + sets is_online=true
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
- Active trip + trip list + single trip (all with driver lat/lng via ST_Y/ST_X join)
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

---

## Rider Android App — Completed Screens

| Screen | Notes |
|---|---|
| Login | Blue header branding, inline error |
| Register | Email confirm dialog, password mismatch validation |
| Home | OSMDroid full-screen map, vehicle type chips with online count, trip type toggle, pickup/dropoff pin-tap via PickerMode, 2-step bottom sheet (form → summary), nearby driver polling every 5s, active trip recovery on load |
| Searching | Radar animation (3 staggered rings), Realtime + 5s polling fallback, cancel with confirm dialog |
| Negotiation | Fare display, round counter, counter input field, accept/counter/reject, Realtime + 5s polling |
| ActiveTrip | Full-screen OSMDroid map, draggable bottom sheet, driver pin polling every 3s, OSRM road route (>50m re-fetch threshold), pickup/dropoff pins, status stepper, call button |
| TripComplete | Trip summary card, fare display, rate prompt |
| RateDriver | 5-star selector, optional comment, haptic on tap |
| TripHistory | Paginated (20/page), shimmer skeleton, pull-to-refresh, infinite scroll, rate button |
| Profile | Avatar upload, inline edit (name/email), island badge, logout with confirm |
| OfflineBanner | Animated, ConnectivityManager NetworkCallback |

### Rider — Other Completed Work
- `DeepLinkHandler` singleton SharedFlow for FCM tap → NavGraph navigation
- `TokenAuthenticator` in OkHttp: silent 401 refresh, clears session on failure
- DataStore for token storage (encrypted at OS level)
- FCM service: foreground routes via DeepLinkHandler, background posts system notification
- Haptic feedback: Tick / Confirm / Error patterns
- Shimmer loading: TripCardSkeleton, ProfileSkeleton
- Theme: full Material3 light color scheme with Antar brand colors

---

## Driver Android App — Completed Screens

| Screen | Notes |
|---|---|
| Login | Email/password, error card |
| Register | Full name/email/phone/password/confirm, email confirm dialog |
| Map | OSMDroid, online/offline toggle (requires active vehicle + GPS + permission), recenter FAB, rating chip, avatar in top bar, active trip recovery on resume, "Lihat Perjalanan" FAB when online |
| Profile | Avatar upload (Coil, cache-busting timestamp), vehicle list, set-active, delete with confirm, earnings + history shortcuts, rating bar, island badge |
| AddVehicle | Vehicle type dropdown, license plate, make/model/year/color |
| Earnings | Summary cards (today/week/month/all-time), 7-day bar chart (Canvas), rating card, pull-to-refresh |
| IncomingTrips | Poll every 5s, skeleton loading, offline banner, distance label, errand note card |
| OfferPrice | Fare stepper (+/- 1000 IDR), floor price enforcement, trip summary card |
| WaitingForRider | Realtime subscription (timestamp-suffixed channel name), spinning clock animation, shows offered fare |
| CounterDecision | Accept/Counter/Reject rider's counter, fare stepper, driver counter limit display, floor enforcement |
| ActiveTrip | Trip detail (rider info, call button, addresses, fare, start/complete/cancel), map pending (TODO-3) |
| RateRider | 5-star tap selector, optional comment, skip option |
| TripHistory | Paginated, skeleton, infinite scroll, rate button for unrated completed trips |

### Driver — Other Completed Work
- `LocationService`: foreground service, captures JWT at start, `syncImmediateLocation()` on go-online, periodic GPS every 3s, `goOffline()` on destroy via `runBlocking`
- `AuthInterceptor`: silent 401 refresh, `ACTION_SESSION_EXPIRED` broadcast on failure
- `SessionManager`: EncryptedSharedPreferences (AES256-GCM)
- `ConnectivityObserver`: singleton NetworkCallback StateFlow
- `OfflineBanner`: animated expand/collapse
- `RatingBar` + `RatingChip` components with half-star support
- Skeleton components: `SkeletonBox`, `SkeletonTripCard`, `SkeletonHistoryCard`
- `TripSelectionHolder`: object to pass `IncomingTripResponse` to OfferPrice without Navigation serialization
- `WaitingForRiderViewModel`: timestamp suffix on Realtime channel name to prevent reuse conflict
- `SupabaseClientHolder`: singleton Supabase client for Realtime only (auth handled by Go server)
- FCM service (`AntarDriverMessagingService`): `new_trip` → IncomingTrips, `offer_accepted` → ActiveTrip
- Haptic feedback on offer submit, complete, etc.

---

## Known Bugs Fixed

| Bug | Root Cause | Fix Applied |
|---|---|---|
| pgx v5 can't scan `timestamptz` into `string` | pgx v5 strict type scanning | Cast all timestamp columns as `::text` in SQL |
| BEFORE INSERT trigger had NULL `NEW.id` for FK | Trigger fires before row gets its PK | Changed to AFTER INSERT trigger with separate UPDATE |
| `ktor-client-android` has no WebSocket support | Wrong Ktor client artifact | Changed to `ktor-client-okhttp` in build.gradle.kts |
| pgx v5 rejects unused `$N` params | pgx strict param validation | Removed unused params and renumbered |
| NegotiationScreen 500 when trip is null | `tripSelect` missing ::text casts | Added ::text casts + null-trip error branch in screen |
| `java.util.Properties` unresolved in build.gradle.kts | Missing import | Added `import java.util.Properties` and `import java.io.FileInputStream` |
| WaitingForRider Realtime channel reuse conflict | Channel name collision on back-navigate | Added `System.currentTimeMillis()` suffix to channel name |
| Stale is_online=true after app kill | LocationService.onDestroy not called by OS on force-kill | `isRunning` static flag checked in MapViewModel.refreshProfile() to detect and clean up stale state |
| Driver map marker doesn't recenter after navigating back | `AndroidView` recreated, initialCenterDone resets | `initialCenterDone` is `remember { mutableStateOf(false) }` — resets per composition, re-centers on first GPS fix |
| islands and driver_notification_queue had no RLS | Tables created without RLS enabled | RLS enabled with appropriate policies via migration |

---

## Database — Current Live State (May 2026)

- **Vehicle types:** Car (disabled), Motorbike (enabled), Bentor (enabled)
- **Islands:** Karakelang 3000m, Kabaruan 1500m, Salibabu 1000m, Sara Besar 500m, Sara Kecil 300m — all with real GeoJSON polygon boundaries
- **Payment methods:** Cash (enabled), Bank Transfer (disabled), E-Wallet (disabled)
- **Negotiation setting:** max_negotiation_rounds = 6 (rider gets 3, driver gets 3)
- **All 22 migrations applied**, no pending migrations
- **RLS enabled** on all public tables including islands and driver_notification_queue
