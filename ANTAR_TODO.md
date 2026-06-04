# Antar — Master TODO
**Last updated:** June 2026
**Paste alongside ANTAR_CONTEXT.md at the start of every session.**
**Fix in order within each group — items within the same file are batched together.**

---

## Priority Legend
- 🔴 Critical — fix before any real users
- 🟠 High — fix before production launch
- 🟡 Medium — important but not blocking
- 🔵 Low / Polish
- ⏳ Pending — do after all others are done

---

## 1. Supabase Database (RLS Migrations)

All items here are a single migration file touching RLS policies only.

**File:** new migration in `supabase/migrations/`

- 🔴 **SEC-1** — "service all" policies on `app_settings`, `fare_rules`, `islands`, `payment_methods`, `trips`, `driver_notification_queue` use `roles = public`. Recreate each as `roles = service_role`. Revoke all grants on `driver_notification_queue` from anon and authenticated.
- 🔴 **SEC-2** — `driver_profiles` and `rider_profiles` SELECT/UPDATE policies use broken `EXISTS(SELECT 1 FROM auth.users WHERE users.id = profile.id)` — always true for any row. Remove the `OR EXISTS(...)` fallback, keep only `auth.uid() = id`. Drop `rider_profiles: service insert` (with_check=true, public) and recreate as service_role only.
- 🟠 **SEC-3** — `trips: driver select relevant` allows any authenticated user to read all `requested` trips including rider GPS and addresses. Add a join check that the querying user is a driver with matching `vehicle_type_id` and `island_id`. Follow the same join pattern in `IncomingTrips` handler.
- 🟠 **SEC-4** — `ratings: public read` exposes all scores, comments, and user UUIDs unauthenticated. Replace with `auth.uid() = rater_id OR auth.uid() = ratee_id`, authenticated only.
- 🟠 **SEC-5** — `spatial_ref_sys` has RLS disabled and anon has full write grants. Revoke INSERT/UPDATE/DELETE from anon and authenticated. Enable RLS with SELECT-only public policy.

---

## 2. Antar-Server

### `internal/driver/handler.go`

- 🟠 **SEC-7** — `IncomingTrips()` and `NearbyDrivers()` build SQL via `fmt.Sprintf` string interpolation for `vehicle_type_id` and `vehicleTypeClause`. Move these into positional `$N` parameters. Follow the parameterised pattern used everywhere else in the same file.
- 🟡 **EARN-TZ** — `GetDailyEarnings()` uses `t.created_at::date` which is UTC. Talaud is WITA (UTC+8) so trips after 16:00 UTC appear on the wrong day in the earnings chart. Change to `(t.created_at AT TIME ZONE 'Asia/Makassar')::date`.
- 🟡 **NEG-TIMEOUT** — Offered trips have no expiry. If a driver goes offline mid-negotiation the trip stays `offered` forever. Add a pg_cron job (or extend the existing timeout function) that resets `offered` trips back to `requested` after N minutes of inactivity, using `updated_at` as the clock.

### `internal/rider/handler.go`

- 🟠 **REQ-DUP** — `RequestRide` has no duplicate guard. If the network drops after the server INSERTs but before the response arrives, a retry creates a second trip row. Add a unique partial index or a `WHERE NOT EXISTS` check on `rider_id + status IN ('requested','offered','agreed','in_progress')` before INSERT.

### `internal/middleware/` + `internal/admin/routes.go`

- 🟠 **SEC-6** — Admin routes use the same `middleware.Auth(cfg)` as user routes — any valid driver or rider JWT can call admin endpoints. Create an `AdminOnly()` middleware that checks the authenticated user ID against an `admin_users` table (create this table). Apply it to all routes in `admin/routes.go`.
- 🟡 **SEC-8** — No rate limiting on login or offer submission. Add per-IP limiting on `POST /login` (driver and rider) and per-user limiting on `POST /trips/:id/offer`. Use a Gin-compatible rate limiter library (e.g. `ulule/limiter`).

---

## 3. Rider App (Antar)

### `ui/home/HomeViewModel.kt`

- 🟠 **REQ-DUP** — `requestRide()` has no guard against duplicate submission. On a slow connection the rider can tap "Pesan Sekarang" twice before the first response arrives, sending two trip requests. Set a `bookingLoading = true` guard before the network call and ensure it's checked before proceeding — it already exists but verify it's set before the coroutine suspends, not inside it.

### `ui/trip/SearchingViewModel.kt`

- 🟡 **CONN-1** — `fetchTrips()` fails silently inside `runCatching` when connectivity drops. The radar animation keeps spinning with no user feedback. Expose a connection error state and show a banner or message when polling has failed for more than 2 consecutive attempts.

### `ui/trip/NegotiationViewModel.kt` + `ui/trip/NegotiationScreen.kt`

- 🟡 **CONN-2** — `accept()`, `reject()`, `submitCounter()` are single HTTP calls with no double-tap protection. On a slow connection a second tap sends a duplicate request. Set an `actionLoading` guard that persists until the response arrives and blocks re-entry.
- 🟡 **NEG-TIMEOUT** — If the driver goes offline mid-negotiation, the trip stays `offered` and the rider has no way to cancel from `NegotiationScreen`. Add a cancel option (or surface the timeout state) once the server-side timeout is implemented.
- 🟡 **NEG-REJECT** — `NegotiationScreen` "Tolak" button has no confirmation dialog. One accidental tap resets the entire negotiation. Add an `AlertDialog` confirmation matching the pattern used in `SearchingScreen.cancelTrip`.

### `ui/trip/ActiveTripViewModel.kt`

- 🟡 **CONN-3** — Status polling (6s) and Realtime both fail silently on connection loss. The screen goes stale with no feedback. Add a `isConnectionLost` state that triggers after N consecutive poll failures, and surface it via the offline banner already used in other screens.
- 🟡 **CONN-4** — `routePoints` is not retried after connectivity restores. After the `startRouteRetry()` exhausts its 5 attempts, if the network comes back the route never appears. Listen for connectivity restoration (use `rememberConnectivityState` already in `Offlinebanner.kt`) and trigger a fresh route fetch when it transitions back to online.
- 🟡 **CONN-5** — If `completeTrip` (via Realtime or polling) is missed due to connectivity loss, the rider's screen never transitions to `TripComplete`. The existing polling at 6s should catch it eventually, but add a visual indicator if the trip status hasn't updated for more than 30 seconds.

### `ui/trip/ActiveTripScreen.kt`

- 🟡 **CONN-3** — Add `OfflineBanner` at the top of `ActiveTripScreen`. The component already exists in `ui/common/Offlinebanner.kt` — wire it using `rememberConnectivityState()`.

### `ui/history/TripHistoryScreen.kt`

- 🟡 **RATE-DUP** — `riderHasRated` is only fetched at list load time. If the rider rates from `TripCompleteScreen` then opens history before the list refreshes, the "Nilai" button reappears. Show a loading state on the button after tap and handle the "already rated" error from the server gracefully (show "Sudah dinilai" instead of a generic error).

### `ui/auth/AuthViewModel.kt` + `RegisterScreen.kt`

- 🟡 **VALID-1** — No phone number format validation on registration. Validate that the number starts with `08` or `+62` before submitting. Show an inline error matching the existing pattern for password mismatch.

### `navigation/NavGraph.kt`

- 🟡 **DEEP-1** — `DeepLinkHandler` has `extraBufferCapacity = 1`. If two notifications arrive while the app is killed, only the last survives. Not easily fixable without a persistent queue, but add a comment and consider replacing with an Intent extra approach (already used in DriverAntar's `MainActivity`) for cold-start scenarios.

---

## 4. Driver App (DriverAntar)

### `ui/trip/WaitingForRiderViewModel.kt`

- 🔴 **CONN-6** — `WaitingForRiderViewModel` has **only Realtime, no polling fallback**. If the WebSocket drops on poor Talaud connectivity, `uiState` stays permanently at `Waiting` with no recovery path. Add a 5s polling fallback calling `getActiveTrip()` — same dual Realtime+polling pattern already used in `SearchingViewModel` and `IncomingTripsViewModel`. This is the most critical connectivity fix in the entire app.

### `ui/trip/ActiveTripViewModel.kt`

- 🟡 **CONN-7** — `arriveAtPickup()`, `startTrip()`, `completeTrip()` are single HTTP calls with no retry. Add an `actionLoading` guard to prevent double-tap on slow connections — pattern already used in `OfferPriceViewModel`.
- 🟡 **CONN-8** — Realtime channel drops on connection loss with no recovery. Add the same polling fallback pattern (`getActiveTrip()` every 6s) alongside the existing Realtime subscription.
- ⏳ **DROPOFF-GUARD** — `canComplete` returns `true` when `dropoff_lat == 0.0` on transport trips, allowing completion without reaching the destination. **Pending — fix after all other issues are resolved.** Note: needs coordinated fix in both this file (proximity check) and `ActiveTripScreen.kt` (button state), and requires verifying that `dropoff_lat` is always populated correctly from the server before enabling the guard.

### `ui/trip/IncomingTripsScreen.kt` + `IncomingTripsViewModel.kt`

- 🟡 **ONLINE-CHECK** — Driver can submit an offer even if `LocationService.isRunning` is false (went offline in another tab, service crashed). Check `LocationService.isRunning` in `IncomingTripsScreen` before allowing trip tap — if false, show a snackbar prompting the driver to go online first.

### `ui/service/LocationService.kt`

- 🟠 **ANR-1** — `goOffline()` in `onDestroy()` uses `runBlocking` on the main thread. On a slow or dropped connection this blocks and can cause an ANR. Move to a `TimeoutCancellationException`-guarded call with a hard 3s timeout — if it fails, let the pg_cron timeout handle the server state cleanup.
- 🟠 **INIT-RACE** — `START_STICKY` causes Android to restart the service after a kill, but `SessionManager.init()` is only called in `MainActivity.onCreate()`. If Android restarts the service before the user reopens the app, `capturedToken` is blank and all location updates fail silently. Add a `SessionManager.init(applicationContext)` call at the top of `onStartCommand` as a safety guard.

### `data/remote/RetrofitClient.kt`

- 🟠 **TOKEN-RACE** — `AuthInterceptor` uses `@Volatile isRefreshing` but a second simultaneous 401 still returns null and fails that request. Replace the boolean flag with a `Mutex` (from `kotlinx.coroutines.sync`) so the second request waits for the refresh to complete and then retries with the new token instead of failing.

### `utils/ConnectivityObserver.kt`

- 🟡 **CONN-9** — `onLost` sets `_isOnline.value = cm.activeNetwork != null`. `activeNetwork` can still return the lost network briefly after `onLost` fires, causing a false-positive online state. Set `_isOnline.value = false` unconditionally in `onLost`.

### `ui/trip/TripHistoryScreen.kt`

- 🟡 **DATE-1** — `formatDate()` uses `Locale.getDefault()` to parse ISO timestamps. Fails on devices with non-Gregorian calendars (possible on some Indonesian devices). Use `Locale.US` for parsing, `Locale("id","ID")` for display only.

### `ui/navigation/AppNavGraph.kt`

- 🟡 **HOLDER-1** — `TripSelectionHolder.selectedTrip` is cleared `onBack` but not on `onDispose` of the `OfferPrice` composable. A configuration change while on `OfferPrice` can leave a stale reference. Clear it in the `DisposableEffect` of the `OfferPrice` composable destination.

### `ui/auth/AuthViewModel.kt` + `RegisterScreen.kt`

- 🟡 **VALID-1** — Same as rider app. Validate phone number format (`08` or `+62`) before registration submit.


INTERNET CONNECTION ISSUE



Stage 1 — Rider requests a ride (HomeViewModel.requestRide)
Single HTTP call wrapped in runCatching. If the network drops mid-call and the server actually processed the INSERT but the response never arrives, the client gets an exception → shows "Tidak dapat terhubung ke server" → rider thinks it failed. If they retry, a second trip row gets created in the DB. The server has no duplicate guard (no unique constraint on rider_id + status = requested). Rider could have two open trips.

Stage 2 — Searching (SearchingViewModel)
Has both Realtime + 5s polling fallback — good. But:

If connectivity drops, both fail silently inside runCatching. No feedback to the rider that polling has stopped. The spinning radar animation keeps playing as if everything is fine.
cancelTrip() is a single HTTP call with no retry. If it fails, the rider sees the error but the trip stays requested in the DB. Rider is stuck — can't cancel, can't proceed. The trip will eventually time out via pg_cron but that could take minutes.


Stage 3 — Negotiation (NegotiationViewModel — rider)
Has both Realtime + 5s polling — good. But:

accept(), reject(), submitCounter() are all single HTTP calls. On failure actionLoading resets and an error string shows. But if the network is slow rather than down, the rider can double-tap — two accept calls race to the server. The server's AcceptOffer uses an UPDATE WHERE status = 'offered' so only one wins, but the second call returns an error that the rider sees as a failure even though the first succeeded.
actionLoading is not persisted through the state — if the app goes background and comes back during a slow call, the button is re-enabled.


Stage 4 — Driver waiting for rider (WaitingForRiderViewModel)
This is the most fragile point in the entire flow. It has only Realtime, no polling fallback at all. If the WebSocket drops — which is expected on poor Talaud island connectivity — uiState stays permanently at WaitingUiState.Waiting. The driver has no idea what happened to the trip. They can't navigate away, there's no timeout, and there's no retry button. The rider could have accepted, countered, or cancelled — the driver will never know until they kill and reopen the app.

Stage 5 — Active trip, driver (ActiveTripViewModel — DriverAntar)

arriveAtPickup(), startTrip(), completeTrip() are single HTTP calls. completeTrip() failing means the trip stays in_progress in the DB. The driver sees an error and can retry, but the rider sees nothing — their active trip screen just keeps polling stale data.
LocationService.syncWithServer() fails silently on connection loss. The driver's position stops updating in the DB, so the rider's map shows the driver frozen at their last known position.
goOffline() in LocationService.onDestroy() uses runBlocking — on a very slow or dropped connection this blocks the main thread and can cause an ANR when the driver goes offline or the app is killed.


Stage 6 — Active trip, rider (ActiveTripViewModel — Antar)

Status polling every 6s fails silently inside runCatching. No offline banner on ActiveTripScreen — the rider has no idea the screen has gone stale.
Realtime channel drops on connection loss. The Supabase SDK will attempt to reconnect but there's no explicit handler if it can't — the channel just stays dead.
OsrmRouteHelper has a 6s timeout and returns null on failure. routePoints stays empty or stale — no retry after connectivity restores, no indicator to the rider that the route failed.
startStatusPolling and Realtime both reload the trip on status change. But if connectivity is lost exactly when the driver presses "Mulai Perjalanan", the rider's screen never transitions from agreed to in_progress, so the stepper stays on the wrong step indefinitely.

Things that are broken or will break in production
1. Driver can complete a trip without a dropoff pin on transport trips
In ActiveTripViewModel (DriverAntar), canComplete is:
kotlinif (t.trip_type == "errand" || t.dropoff_lat == 0.0) return true
If dropoff_lat is 0.0 on a transport trip — which can happen if the rider typed an address but geocoding failed — the driver can complete the trip immediately without going anywhere. Should be a hard block, not a pass.
2. No check if driver is still online when accepting a trip
OfferPrice lets a driver submit an offer even if LocationService has stopped (they went offline in another tab, crashed, etc.). The server's atomic lock prevents double-offers but a driver whose service has died can still lock a trip, leaving the rider waiting for a driver who is effectively offline.
3. Token refresh race condition (DriverAntar)
AuthInterceptor.refreshAccessToken() has @Volatile private var isRefreshing but if two requests 401 simultaneously, the second enters if (isRefreshing) return null and returns null — meaning that second request fails even though a valid new token is about to be saved. On a slow connection with parallel requests (location update + trip poll both expiring at the same time) this logs the driver out unnecessarily.
4. LocationService restart after system kill
START_STICKY means Android will restart the service after killing it, but onStartCommand requires location permission check and calls startForeground. If Android restarts it in the background before the driver reopens the app, SessionManager may not be initialised yet (it's initialised in MainActivity.onCreate) — capturedToken will be blank, location updates silently fail, driver appears offline to riders.
5. Avatar upload has no file type validation on Android
Both apps pick image/* from gallery and send whatever MIME type the content resolver returns. The server accepts image/jpeg, image/png, image/webp but the Android side doesn't validate before uploading. A user can select a .gif or .heic file — the server will save it with a .jpg extension but the content will be invalid, breaking the avatar display permanently.

UX problems that will confuse real users
6. No deep link / notification tap handling when app is fully killed (rider)
DeepLinkHandler uses MutableSharedFlow(extraBufferCapacity = 1). If the rider taps a "Driver offer" notification that cold-starts the app, MainActivity.handleFcmIntent calls DeepLinkHandler.emit() before AntarNavGraph has subscribed to the flow. The event is buffered (capacity=1) so it should work — but only for the first notification. If two notifications arrive while the app is killed only the second survives the buffer. On poor connectivity drivers often retry offers quickly, so this is realistic.
7. Negotiation screen has no timeout
A rider can sit on NegotiationScreen indefinitely. If the driver goes offline mid-negotiation, the trip status stays offered forever — the rider has no way to cancel from that screen and no indication anything is wrong. The pg_cron timeout only applies to the initial notification queue, not to offered trips.
8. Driver earnings screen timezone is wrong
GetDailyEarnings in the server uses t.created_at::date which is UTC. Talaud is WITA (UTC+8). A trip completed at 23:00 local time appears on the next day's bar in the chart. The server code even has a comment about this but it was never fixed.
9. Rider can rate a driver multiple times from history
TripHistoryScreen (rider) shows a "Nilai" button when !trip.riderHasRated. But riderHasRated is only fetched when the list loads. If the rider rates from the TripComplete screen and then opens history before the list refreshes, the button still appears and tapping it hits the server — which correctly rejects it with a unique constraint error, but the rider sees a generic error message instead of "already rated".
10. No confirmation when rider rejects an offer
NegotiationScreen has an immediate "Tolak" button with no confirmation dialog. One accidental tap resets the entire negotiation — the driver has to find the trip again and re-offer. Especially risky on small phone screens.

Missing features that matter for this specific market
11. No offline trip history cache
If a driver opens TripHistory or Earnings with no connection, they see a blank error screen. For drivers in rural Talaud who check their earnings at the end of the day when connectivity may be poor, this is a real usability problem. Even caching the last successful response in DataStore/SharedPreferences would help.
12. No phone number format validation
Registration accepts any string for phone_number. Indonesian mobile numbers should start with 08 or +62. Riders and drivers need to be able to call each other — a garbage phone number in the database means the call button does nothing useful.
13. Driver has no way to contact rider before accepting
Before offering a price, the driver sees the rider's pickup address and the trip details but not their phone number. On a small island where the pickup address might be vague ("depan kantor camat"), the driver has no way to clarify before committing to an offer.
14. No trip receipt / history for completed errand trips
For errand trips, the note field contains the shopping instructions. Once the trip is completed, the rider can see the note in history but there's no structured record of what was purchased or the change returned. For a feature targeting errand use cases this is a gap.
15. Realtime subscription for both apps uses the anon key directly
As flagged in the security audit — but worth restating as a feature gap. Since the anon key is in the APK, anyone can subscribe to your Supabase Realtime channel and listen to all trip status changes. Before going to production the Realtime subscription should go through a short-lived authenticated token or be proxied through the Go server.

Code quality issues worth cleaning up
16. TripSelectionHolder is a global singleton
If the driver navigates to IncomingTrips, taps a trip, then presses back before the screen composes, TripSelectionHolder.selectedTrip holds a stale reference. If they then tap a different trip, there's a window where the old trip's data is still set. Should be cleared on onBack — which it is — but not on onDispose of the OfferPrice composable, so a back-stack configuration change could leave it stale.
17. ConnectivityObserver.onLost logic is wrong
kotlinoverride fun onLost(network: Network) {
    _isOnline.value = cm.activeNetwork != null
}
cm.activeNetwork can still return the lost network for a brief moment after onLost fires. Should be false unconditionally in onLost, letting onAvailable set it back to true.
18. formatDate in TripHistory uses device locale for parsing
kotlinSimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
The server returns ISO timestamps. Parsing with Locale.getDefault() can fail on devices with non-Gregorian calendar systems (some Indonesian devices use Islamic calendar). Should be Locale.US for parsing, Locale("id","ID") only for display.

Summary by category:
CategoryCountWill break in production5UX problems for real users5Missing features for this market5Code quality3


Summary by severity:
#StageIssueSeverity1WaitingForRider (driver)No polling fallback — permanently stuck if Realtime dropsCritical2Request rideDuplicate trip if response lost after server INSERTHigh3Searching + NegotiationSilent polling failure — no user feedback connectivity is lostHigh4Active trip (driver)goOffline() runBlocking can ANR on slow networkHigh5NegotiationDouble-tap on slow network sends duplicate accept/reject callsMedium6Active trip (rider)No offline banner, Realtime dead channel not recoveredMedium7Active trip (driver)completeTrip() failure leaves trip stuck in_progress with no auto-retryMedium8Active trip (rider)Route line not retried after connectivity restoresLow
The single most important fix is #1 — adding a polling fallback to WaitingForRiderViewModel the same way SearchingViewModel already does it.

# Antar — Security TODO

1. [SEC-1] Fix "service all" RLS policies — scope to service_role only
2. [SEC-2] Fix broken EXISTS() policy logic on profile tables
3. [SEC-3] Restrict requested trip visibility to matching drivers only
4. [SEC-4] Lock down ratings — only rater/ratee can read their own rows
5. [SEC-5] Protect spatial_ref_sys from anon writes
6. [SEC-6] Add admin role check middleware on admin routes
7. [SEC-7] Replace fmt.Sprintf SQL interpolation with parameterised queries
8. [SEC-8] Add rate limiting on auth and offer endpoints

---

## SEC-1 — Fix "service all" RLS policies (Critical)

**Why:** Policies use `roles = public` instead of `service_role`, meaning anyone with the anon key (embedded in the APK) has the same DB write access as the Go server, bypassing all server-side validation.

**Affected tables:** `app_settings`, `fare_rules`, `islands`, `payment_methods`, `trips`, `driver_notification_queue`

**What to do:** For each table, drop the existing `* service all` policy and recreate it scoped to `service_role`. Keep existing public read policies untouched.

**Constraint:** `driver_notification_queue` should additionally have `REVOKE ALL FROM anon, authenticated` since no client app should ever directly read FCM tokens from it.

---

## SEC-2 — Fix broken EXISTS() logic on profile policies (Critical + High)

**Why:** `EXISTS(SELECT 1 FROM auth.users WHERE users.id = profile.id)` evaluates to true for any existing profile row, making SELECT and UPDATE policies effectively open to all authenticated users. Affects `driver_profiles` (SELECT, UPDATE), `rider_profiles` (SELECT, UPDATE).

**What to do:** Remove the `OR EXISTS(...)` fallback — keep only `auth.uid() = id`. Also drop `rider_profiles: service insert` (with_check = true, public) and recreate scoped to `service_role`.

---

## SEC-3 — Restrict requested trip SELECT to matching drivers (High)

**Why:** `trips: driver select relevant` allows any authenticated user to read all `requested` trips including pickup address, dropoff address, errand notes, and GPS coordinates of riders.

**Affected policy:** `trips: driver select relevant` in Supabase RLS

**What to do:** The `status = 'requested'` condition needs an additional check that the querying user is an online driver with a matching `vehicle_type_id` and `island_id`. Follow the same island+vehicle_type join pattern used in `IncomingTrips` handler in `driver/handler.go`.

---

## SEC-4 — Restrict ratings visibility (High)

**Why:** `ratings: public read` exposes all scores, comments, rater_id, and ratee_id to unauthenticated requests.

**What to do:** Drop the public read policy, replace with authenticated-only policy where `auth.uid() = rater_id OR auth.uid() = ratee_id`.

---

## SEC-5 — Protect spatial_ref_sys (High)

**Why:** RLS is disabled and anon has full INSERT/UPDATE/DELETE. Corruption breaks all PostGIS queries app-wide.

**What to do:** Revoke INSERT/UPDATE/DELETE from anon and authenticated roles. Enable RLS. Add SELECT-only public policy.

---

## SEC-6 — Add admin role check middleware (Medium)

**Why:** `admin/routes.go` uses the same `middleware.Auth(cfg)` as user routes — any valid driver or rider JWT can call admin endpoints and change fare floors, negotiation settings, etc.

**Files to touch:** `internal/middleware/` (new file), `internal/admin/routes.go`

**What to do:** Create an `AdminOnly()` middleware that checks the authenticated user ID against an `admin_users` table (create this table) or a custom Supabase JWT claim. Apply it to all admin routes.

---

## SEC-7 — Replace fmt.Sprintf SQL interpolation (Medium)

**Why:** Two handlers build SQL strings via string interpolation — safe today (integer values) but a footgun pattern.

**Files to touch:** `internal/driver/handler.go`

**Affected functions:** `IncomingTrips()` — `vehicleTypeClause` interpolation. `NearbyDrivers()` — same pattern.

**What to do:** Move the `vehicle_type_id` filter into a positional `$N` parameter instead of `fmt.Sprintf` into the query string. Follow the parameterised pattern used everywhere else in the same file.

---

## SEC-8 — Add rate limiting on sensitive endpoints (Medium)

**Why:** No rate limiting on login or offer submission — vulnerable to credential stuffing and offer-spam racing.

**Files to touch:** `internal/middleware/` (new or extended), `internal/driver/routes.go`, `internal/rider/routes.go`

**What to do:** Add per-IP rate limiting on `POST /login` (both driver and rider) — suggest 5 req/min. Add per-user rate limiting on `POST /trips/:id/offer` — suggest 10 req/min. Use a Gin-compatible rate limiter library (e.g. `ulule/limiter`).
