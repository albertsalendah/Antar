# Antar — Master TODO
**Last updated:** June 2026
**Paste this file + ANTAR_CONTEXT.md at the start of every session.**

---

## What Was Done Last Session
- `SEC-1` ✅ — All "service all" policies scoped to `service_role`; driver_notification_queue anon/authenticated grants revoked.
- `SEC-2` ✅ — Broken `OR EXISTS(auth.users)` fallback removed from driver_profiles and rider_profiles policies.
- `SEC-3` ✅ — `trips: driver select relevant` now requires island_id + vehicle_type_id + is_online=true match.
- `SEC-4` ✅ — `ratings` SELECT scoped to authenticated rater/ratee only.
- `SEC-7` ✅ — `IncomingTrips` and `NearbyDrivers` use parameterised queries (no fmt.Sprintf).
- `SEC-8` ✅ — Rate limiting applied to login and offer endpoints via ulule/limiter/v3.
- `NEG-TIMEOUT` ✅ — `process_trip_notification_timeouts()` now resets stale `offered` trips after 10 min.
- `EARN-TZ` ✅ — `GetDailyEarnings` uses Asia/Makassar timezone.
- `REQ-DUP-SRV` ✅ — `RequestRide` duplicate guard added.
- `TODO-4A/4B/4C` ✅ — Realtime driver location tracking completed in rider app.

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
| 20 | TODO-7 | 🔵 Low | Both | Route line not showing — OSRM no road data for Talaud, need straight-line fallback |
| 21 | DEEP-1 | 🔵 Low | Rider | `DeepLinkHandler` buffer=1 — second notification lost if app is killed |
| 22 | SEC-5 | 🔵 Low | DB | `spatial_ref_sys` RLS not enabled — PostGIS system table needs SELECT-only policy |
| — | SEC-6 | ⏳ Deferred | Server | Admin routes use same Auth() as user routes — defer until admin panel work begins |
| — | DROPOFF-GUARD | ⏳ Pending | Driver | `canComplete` skips proximity check when `dropoff_lat=0.0` — fix AFTER all CONN/ANR fixes |

---

## 1. Go Server (`Antar-Server`)

No remaining server items at this time. SEC-6 (AdminOnly middleware) is deferred to admin panel phase.

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

**TODO-7** (deferred) — Route line not showing. Fix in `OsrmRouteHelper.kt`: add `straightLine()` fallback when OSRM returns 0 routes. Also update `fetchRouteIfNeeded` to draw straight line when driver location is still 0,0.

### `ui/history/TripHistoryScreen.kt` (rider)

**RATE-DUP** — `riderHasRated` is stale after rating from `TripCompleteScreen`. On "Nilai" tap, set button to loading state immediately. Handle the server's "already rated" unique-constraint error by showing "Sudah dinilai" rather than a generic error.

### `ui/auth/RegisterScreen.kt` + `ui/auth/AuthViewModel.kt` (rider)

**VALID-1** — No phone number format validation. Add check: must start with `08` or `+62` before calling `api.register()`. Show inline error using existing error pattern. Same fix needed in driver app.

### `navigation/NavGraph.kt` (rider)

**DEEP-1** — `DeepLinkHandler` has `extraBufferCapacity = 1`. If two notifications arrive while app is killed, only the last survives. Low priority; add a comment flagging the limitation.

---

## 3. Driver App (`DriverAntar`)

### `ui/trip/WaitingForRiderViewModel.kt` 🔴

**CONN-6** — **Most critical issue in the entire app.** `WaitingForRiderViewModel` has only Realtime, zero polling fallback. If the WebSocket drops on poor Talaud connectivity, `uiState` stays at `Waiting` permanently with no recovery.

Fix: add a 5s polling loop calling `repository.getActiveTrip()`, same dual Realtime+polling pattern used in `SearchingViewModel` (rider) and `IncomingTripsViewModel`. If trip status changed, handle it via the same `handleUpdate()` logic.

### `ui/trip/ActiveTripViewModel.kt` (driver) + `ui/trip/ActiveTripScreen.kt` (driver)

**CONN-7** — `arriveAtPickup()`, `startTrip()`, `completeTrip()`: single HTTP calls with no loading guard — double-tap on slow connection can send two requests. Add `uiState = ActionLoading` guard before each call, same pattern already in `OfferPriceViewModel`.

**CONN-8** — Driver `ActiveTripViewModel` has Realtime for status updates but no polling fallback. If WebSocket drops, status never updates. Add 6s polling of `repository.getActiveTrip()` alongside the existing Realtime subscription.

### `ui/trip/IncomingTripsScreen.kt`

**ONLINE-CHECK** — Driver can tap a trip and submit an offer even if `LocationService.isRunning` is false. In `IncomingTripsScreen`, check `LocationService.isRunning` before allowing `onTripSelected`. If false, show a snackbar asking driver to go online first.

### `ui/service/LocationService.kt`

**ANR-1** — `onDestroy()` calls `goOffline()` via `runBlocking` on the main thread. Wrap in `withTimeout(3_000)` — if it times out, let the pg_cron timeout handle server-side cleanup.

**INIT-RACE** — `START_STICKY` causes Android to restart the service before `SessionManager.init()` is called. Add `SessionManager.init(applicationContext)` at the top of `onStartCommand` as a safety guard.

### `data/remote/RetrofitClient.kt`

**TOKEN-RACE** — `AuthInterceptor` uses `@Volatile isRefreshing` but a second simultaneous 401 returns `null` immediately and fails that request even though a refresh is about to succeed. Replace the boolean with a `Mutex` (`kotlinx.coroutines.sync`) so the second request suspends until the refresh completes.

### `utils/ConnectivityObserver.kt`

**CONN-9** — `onLost`: `_isOnline.value = cm.activeNetwork != null` may be a false-positive. Change to unconditional `_isOnline.value = false` in `onLost`.

### `ui/trip/TripHistoryScreen.kt` (driver)

**DATE-1** — `formatDate()` parses ISO timestamps with `Locale.getDefault()`. Use `Locale.US` for parsing, `Locale("id","ID")` for display only.

### `ui/navigation/AppNavGraph.kt` + `ui/trip/TripSelectionHolder.kt`

**HOLDER-1** — `TripSelectionHolder.selectedTrip` not cleared on composable dispose. Add `DisposableEffect(Unit) { onDispose { TripSelectionHolder.selectedTrip = null } }` inside the OfferPrice composable block in `AppNavGraph.kt`.

### `ui/auth/RegisterScreen.kt` + `ui/auth/AuthViewModel.kt` (driver)

**VALID-1** — Same as rider: no phone number format validation. Must start with `08` or `+62`.

---

## 4. Database / Security (`Antar-Server/supabase/migrations/`)

### SEC-5 — `spatial_ref_sys` RLS
`spatial_ref_sys` is a PostGIS system table exposed in the public schema with RLS disabled. Enable RLS and add a SELECT-only policy; revoke INSERT/UPDATE/DELETE from `anon` and `authenticated`. Single migration, one table.

### SEC-6 — Admin middleware (deferred)
Admin routes use the same `middleware.Auth()` as user routes — any valid driver/rider JWT can call admin endpoints. Create `AdminOnly()` middleware and an `admin_users` table. **Defer until admin panel development begins.**

---

## ⏳ Pending (Do Last)

**DROPOFF-GUARD** — Driver `ActiveTripViewModel.kt`: `canComplete` returns `true` when `dropoff_lat == 0.0` on transport trips.

> ⚠️ Fix only after all CONN/ANR/TOKEN fixes above are done. Verify `dropoff_lat` is reliably populated from the server in all scenarios before enabling the proximity guard.
