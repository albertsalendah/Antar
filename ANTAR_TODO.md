# Antar — Master TODO
**Last updated:** June 2026
**Paste this file + ANTAR_CONTEXT.md at the start of every session.**

---

## What Was Done Last Session
- `TODO-4A` ✅ — Supabase migration `rider_read_driver_profiles_realtime` applied; `driver_profiles` already in Realtime publication; RLS SELECT policy added for authenticated riders.
- `TODO-4B` ✅ — `LocationTracker.kt` rewritten with `RealtimeLocationTracker` (Realtime primary, 15s polling fallback). Output file: `LocationTracker.kt`.
- `TODO-4C` ✅ — Rider `ActiveTripViewModel` updated: tracker created lazily after trip loads (needs `driverId`), `_driverLocation` MutableStateFlow exposed as `driverLocation`. Output file: `Activetripviewmodel_rider.kt`.
- `TODO-7` (route line bug) — diagnosed but deferred by user. Root cause: OSRM has no road data for Talaud; fix is straight-line fallback in `OsrmRouteHelper.kt` + `RouteHelper.kt`.

---

## Fix Priority Order

| # | ID | Severity | App | Short description |
|---|---|---|---|---|
| 1 | CONN-6 | 🔴 Critical | Driver | WaitingForRider has no polling fallback — stuck if Realtime drops |
| 2 | ANR-1 | 🟠 High | Driver | LocationService `goOffline()` blocks main thread with `runBlocking` |
| 3 | INIT-RACE | 🟠 High | Driver | LocationService restarts (START_STICKY) before SessionManager is init'd |
| 4 | TOKEN-RACE | 🟠 High | Driver | AuthInterceptor refresh race — second 401 fails even if refresh succeeds |
| 5 | REQ-DUP | 🟠 High | Rider | `requestRide()` has no guard — slow connection sends duplicate trip requests |
| 6 | CONN-1 | 🟡 Medium | Rider | SearchingViewModel: polling failure silent — radar spins with no feedback |
| 7 | CONN-2 | 🟡 Medium | Rider | NegotiationViewModel: accept/reject/counter no double-tap guard |
| 8 | NEG-REJECT | 🟡 Medium | Rider | "Tolak" button no confirm dialog — one tap resets entire negotiation |
| 9 | CONN-3 | 🟡 Medium | Rider | ActiveTripScreen missing OfflineBanner + stale state on connection loss |
| 10 | CONN-7 | 🟡 Medium | Driver | arriveAtPickup/startTrip/completeTrip no loading guard — double-tap risk |
| 11 | CONN-8 | 🟡 Medium | Driver | Driver ActiveTrip has only Realtime, no polling fallback for status |
| 12 | ONLINE-CHECK | 🟡 Medium | Driver | Driver can submit offer when LocationService not running |
| 13 | CONN-9 | 🟡 Medium | Driver | `ConnectivityObserver.onLost` reads `activeNetwork` — may be false-positive |
| 14 | RATE-DUP | 🟡 Medium | Rider | `riderHasRated` stale in history — "Nilai" reappears after rating |
| 15 | VALID-1 | 🟡 Medium | Both | No phone number format validation on registration |
| 16 | DATE-1 | 🟡 Medium | Driver | `formatDate` uses device locale for parsing — fails on some Indonesian devices |
| 17 | HOLDER-1 | 🟡 Medium | Driver | `TripSelectionHolder` not cleared on composable dispose (config change) |
| 18 | CONN-4 | 🔵 Low | Rider | Route not retried after connectivity restores |
| 19 | CONN-5 | 🔵 Low | Rider | No visual indicator if trip status update missed >30s |
| 20 | EARN-TZ | 🔵 Low | Server | Earnings daily chart uses UTC — Talaud is WITA (UTC+8) |
| 21 | NEG-TIMEOUT | 🔵 Low | Server | Offered trips never expire — stays offered forever if driver goes offline |
| 22 | REQ-DUP-SRV | 🔵 Low | Server | `RequestRide` no duplicate guard if response lost after INSERT |
| 23 | TODO-7 | 🔵 Low | Both | Route line not showing — OSRM no road data for Talaud, need straight-line fallback |
| 24 | DEEP-1 | 🔵 Low | Rider | `DeepLinkHandler` buffer=1 — second notification lost if app is killed |
| — | DROPOFF-GUARD | ⏳ Pending | Driver | `canComplete` skips proximity check when `dropoff_lat=0.0` — fix AFTER all others |

---

## 1. Go Server (`Antar-Server`)

### `internal/driver/handler.go`

**EARN-TZ** — `GetDailyEarnings`: `t.created_at::date` is UTC; trips after 16:00 UTC appear on wrong day for WITA (UTC+8) users. Change to `(t.created_at AT TIME ZONE 'Asia/Makassar')::date`.

**NEG-TIMEOUT** — No expiry on offered trips. If driver goes offline mid-negotiation the trip stays `offered` forever. Extend `process_trip_notification_timeouts()` in the pg_cron SQL function to also reset `offered` trips back to `requested` after N minutes of inactivity (use `updated_at` as clock). Both handler and migration touched.

### `internal/rider/handler.go`

**REQ-DUP-SRV** — `RequestRide`: no duplicate guard. Add a `WHERE NOT EXISTS` check for `rider_id + status IN ('requested','offered','agreed','in_progress')` before INSERT, or a unique partial index. Prevents ghost trips when network drops after server processes the INSERT.

---

## 2. Rider App (`Antar`)

### `ui/home/HomeViewModel.kt`

**REQ-DUP** — `requestRide()`: `bookingLoading` guard exists but is set inside the coroutine, not before launch. A second tap between the launch and the first suspension can still send two requests. Move `bookingLoading = true` to before the `viewModelScope.launch {}` call.

### `ui/trip/SearchingViewModel.kt`

**CONN-1** — `fetchTrips()` swallows all errors in `runCatching`. When polling has failed 2+ consecutive times, set an exposed error state and surface it in `SearchingScreen` — stop letting the radar animation spin as if nothing is wrong.

### `ui/trip/NegotiationViewModel.kt` + `ui/trip/NegotiationScreen.kt`

**CONN-2** — `accept()`, `reject()`, `submitCounter()`: no guard prevents a second tap while a request is in flight. Add an `actionLoading` flag that is set before the network call and cleared in the result handler, same pattern used in `OfferPriceViewModel`.

**NEG-REJECT** — `NegotiationScreen` "Tolak" button triggers immediately with no confirm dialog. Add `AlertDialog` on tap matching the pattern in `SearchingScreen.cancelTrip`. Files: `NegotiationScreen.kt` + `NegotiationViewModel.kt` (add `showRejectDialog` state).

### `ui/trip/ActiveTripViewModel.kt` + `ui/trip/ActiveTripScreen.kt`

**CONN-3** — Two fixes in the same file pass:
1. `ActiveTripScreen.kt`: add `OfflineBanner` at top, wire via `rememberConnectivityState()` already in `Offlinebanner.kt`.
2. `ActiveTripViewModel.kt`: after N consecutive `getTrip` poll failures, set a `connectionLost` state that the screen can surface.

**CONN-4** — `routePoints` never retried after connectivity restores. In `ActiveTripScreen.kt`, observe connectivity state changes; when transitioning back to online, call a new `viewModel.retryRoute()` method that resets `lastRouteFetchLat/Lng` to 0 and triggers `fetchRouteIfNeeded`.

**CONN-5** — If `completed` is missed for >30s (Realtime dead + polling slow), add a timestamp-based UI hint ("Perjalanan mungkin sudah selesai, tarik untuk refresh") after a 30s threshold without a status change.

**TODO-7** (deferred) — Route line not showing. Fix in `OsrmRouteHelper.kt`: add `straightLine()` fallback when OSRM returns 0 routes (`.getOrElse { straightLine(...) }` instead of `.getOrNull()`). Also update `fetchRouteIfNeeded` to draw pickup→dest straight line when driver location is still 0,0.

### `ui/history/TripHistoryScreen.kt` (rider)

**RATE-DUP** — `riderHasRated` is stale after rating from `TripCompleteScreen`. On "Nilai" tap, set button to loading state immediately. Handle the server's "already rated" unique-constraint error by showing "Sudah dinilai" rather than a generic error. No ViewModel change needed — just UI guard in the screen.

### `ui/auth/RegisterScreen.kt` + `ui/auth/AuthViewModel.kt` (rider)

**VALID-1** — No phone number format validation. Add check: must start with `08` or `+62` before calling `api.register()`. Show inline error using existing error pattern. Same fix needed in the driver app — see driver section.

### `navigation/NavGraph.kt` (rider)

**DEEP-1** — `DeepLinkHandler` has `extraBufferCapacity = 1`. If two notifications arrive while app is killed, only the last survives. Low priority; add a comment flagging the limitation. Consider switching to Intent extras approach used in DriverAntar's `MainActivity` for cold-start reliability.

---

## 3. Driver App (`DriverAntar`)

### `ui/trip/WaitingForRiderViewModel.kt` 🔴

**CONN-6** — **Most critical issue in the entire app.** `WaitingForRiderViewModel` has only Realtime, zero polling fallback. If the WebSocket drops on poor Talaud connectivity, `uiState` stays at `Waiting` permanently with no recovery — driver has no idea if rider accepted, cancelled, or countered.

Fix: add a 5s polling loop calling `repository.getActiveTrip()`, same dual Realtime+polling pattern used in `SearchingViewModel` (rider) and `IncomingTripsViewModel`. If trip status changed, handle it via the same `handleUpdate()` logic.

### `ui/trip/ActiveTripViewModel.kt` (driver) + `ui/trip/ActiveTripScreen.kt` (driver)

**CONN-7** — `arriveAtPickup()`, `startTrip()`, `completeTrip()`: single HTTP calls with no loading guard — double-tap on slow connection can send two requests. Add `uiState = ActionLoading` guard before each call, same pattern already in `OfferPriceViewModel`.

**CONN-8** — Driver `ActiveTripViewModel` has Realtime for status updates but no polling fallback. If WebSocket drops, status never updates. Add 6s polling of `repository.getActiveTrip()` alongside the existing Realtime subscription. Follow the dual-pattern in `WaitingForRiderViewModel` fix above.

### `ui/trip/IncomingTripsScreen.kt`

**ONLINE-CHECK** — Driver can tap a trip and submit an offer even if `LocationService.isRunning` is false (service crashed, user went offline in another screen). In `IncomingTripsScreen`, check `LocationService.isRunning` before allowing `onTripSelected`. If false, show a snackbar asking driver to go online first.

### `ui/service/LocationService.kt`

**ANR-1** — `onDestroy()` calls `goOffline()` via `runBlocking` on the main thread. On a slow/dropped connection this can block and cause an ANR. Wrap in `withTimeout(3_000)` — if it times out, let the pg_cron timeout handle the server-side cleanup.

**INIT-RACE** — `START_STICKY` causes Android to restart the service after a kill, but `SessionManager.init()` is only called in `MainActivity.onCreate()`. If service restarts before the user reopens the app, `capturedToken` is blank and all location updates fail silently. Add `SessionManager.init(applicationContext)` at the top of `onStartCommand` as a safety guard.

### `data/remote/RetrofitClient.kt`

**TOKEN-RACE** — `AuthInterceptor` uses `@Volatile isRefreshing` but a second simultaneous 401 returns `null` immediately and fails that request even though a refresh is about to succeed. Replace the boolean with a `Mutex` (`kotlinx.coroutines.sync`) so the second request suspends until the refresh completes and then retries with the new token.

### `utils/ConnectivityObserver.kt`

**CONN-9** — `onLost`: `_isOnline.value = cm.activeNetwork != null` — `activeNetwork` can still return the lost network briefly after `onLost` fires, causing a false-positive online state. Change to unconditional `_isOnline.value = false` in `onLost`. `onAvailable` will set it back to true when connectivity genuinely restores.

### `ui/trip/TripHistoryScreen.kt` (driver)

**DATE-1** — `formatDate()` parses ISO timestamps with `Locale.getDefault()` — fails on devices with non-Gregorian calendars (possible on some Indonesian devices). Use `Locale.US` for parsing, `Locale("id","ID")` for display only.

### `ui/navigation/AppNavGraph.kt` + `ui/trip/TripSelectionHolder.kt`

**HOLDER-1** — `TripSelectionHolder.selectedTrip` is cleared in `onBack` but not in the `DisposableEffect` of the `OfferPrice` composable destination. A configuration change while on `OfferPrice` leaves a stale reference. Add cleanup in `DisposableEffect(Unit) { onDispose { TripSelectionHolder.selectedTrip = null } }` inside the OfferPrice composable block in `AppNavGraph.kt`.

### `ui/auth/RegisterScreen.kt` + `ui/auth/AuthViewModel.kt` (driver)

**VALID-1** — Same as rider: no phone number format validation. Must start with `08` or `+62`. Follow existing inline error pattern for password validation in the same files.

---

## 4. Database / Security (`Antar-Server/supabase/migrations/`)

All items below are a single migration touching RLS policies only. Batch them into one migration file.

**SEC-1** — "service all" policies on `app_settings`, `fare_rules`, `islands`, `payment_methods`, `trips`, `driver_notification_queue` use `roles = public`. Recreate each scoped to `service_role`. Revoke all grants on `driver_notification_queue` from `anon` and `authenticated`.

**SEC-2** — `driver_profiles` and `rider_profiles` SELECT/UPDATE policies have broken `OR EXISTS(SELECT 1 FROM auth.users WHERE users.id = profile.id)` fallback — always true. Remove the `OR EXISTS(...)` half; keep only `auth.uid() = id`. Drop and recreate `rider_profiles: service insert` scoped to `service_role`.

**SEC-3** — `trips: driver select relevant` exposes all `requested` trips including rider GPS to any authenticated user. Add a join check: querying user must be a driver with matching `vehicle_type_id` and `island_id`. Follow the same join pattern in `IncomingTrips` handler.

**SEC-4** — `ratings: public read` exposes all scores/comments/UUIDs unauthenticated. Replace with `auth.uid() = rater_id OR auth.uid() = ratee_id`, authenticated only.

**SEC-5** — `spatial_ref_sys` has RLS disabled with anon write grants. Revoke INSERT/UPDATE/DELETE from anon and authenticated. Enable RLS with SELECT-only public policy.

**SEC-6** — Admin routes use same `middleware.Auth()` as user routes — any valid driver/rider JWT can call them. Create `AdminOnly()` middleware checking user ID against a new `admin_users` table. Apply in `admin/routes.go`.

**SEC-7** — `IncomingTrips()` and `NearbyDrivers()` in `driver/handler.go` interpolate `vehicle_type_id` via `fmt.Sprintf`. Move into positional `$N` parameters following every other query in the same file.

**SEC-8** — No rate limiting on login or offer endpoints. Add per-IP limiting on `POST /login` (both apps, ~5 req/min) and per-user on `POST /trips/:id/offer` (~10 req/min). Use a Gin-compatible library (e.g. `ulule/limiter`) in `internal/middleware/`.

---

## ⏳ Pending (Do Last)

**DROPOFF-GUARD** — Driver `ActiveTripViewModel.kt`: `canComplete` returns `true` when `dropoff_lat == 0.0` on transport trips, letting drivers complete immediately without reaching the destination.

> ⚠️ Fix this only after all CONN/ANR/TOKEN fixes above are done. Requires verifying that `dropoff_lat` is reliably populated from the server in all scenarios before enabling the proximity guard. Touches `ActiveTripViewModel.kt` (proximity logic) and `ActiveTripScreen.kt` (button state + hint text).

