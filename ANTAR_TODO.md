# Antar — Master TODO
**Last updated:** June 2026 (current session)
**Paste this file at the start of every session.**

---

## Recently Completed — Driver app route/marker pass (this session, applied)

- **Route caching redesign** in `DriverAntar/.../ui/trip/Activetripviewmodel.kt`:
  cache, OSRM-failure cooldown, and destination-binding now live **entirely in the
  ViewModel** (`cachedDestLat`/`cachedDestLng`, `lastOsrmFailureMs`, 5-min
  `OSRM_COOLDOWN_MS`). `RouteHelper.kt` needed **no changes** — this supersedes the
  old ROUTE-FALLBACK-MISSING / OSRM-CACHE-MISSING plan for the driver app, and is
  the pattern to repeat for the rider app (see below).
- **Immediate route draw on trip load** — `loadTrip()` now calls
  `fetchRouteIfNeeded()` using `currentGeoPoint` if a GPS fix is already available,
  fixing the delay where GPS replay arrives before the trip loads.
- **`startTrip()`** resets `cachedDestLat/Lng` + `lastOsrmFailureMs` so the dropoff
  leg gets a fresh OSRM attempt instead of reusing the pickup leg's cache/cooldown.
- **Teardrop pin markers** (`pinDrawable()`) added to `Activetripscreen.kt` for the
  pickup and dropoff/"Tujuan" markers. Driver's own "Posisi Anda" position marker
  stays `circleDrawable` (moving markers keep circles per agreed scope).
- **CONSCRYPT-PLACEMENT ✅ resolved for both apps** — `Security.insertProviderAt`
  moved from `MainActivity.onCreate()` to Application-level `onCreate()` in both
  `Antar.kt` (rider) and `Driverapplication.kt` (driver).

### Earlier completed (background, unchanged)
NavGraph `observeForever` leak fix (StateFlow + LaunchedEffect), missing index on
`trips.notified_driver_id`, `search_path` security fix on 5 DB functions, DEEP-1
buffer fix, rider route-line race fix (`combine(_driverLocation, _trip)` +
`routePoints` LaunchedEffect key), `PollingLocationTracker` 15s→5s, VIBRATE
permission fix, Conscrypt+OkHttp TLS fix for rider OSRM calls.

---

## 🔴 Next Up — Distance/ETA display (driver app first)

Add "distance to destination" and "time to destination" in a **small info card,
top-left corner** of the map on `ActiveTripScreen`. Metric units.

Design discussed but **not yet finalized** — confirm at the start of next session:

1. **Return type change**: `RouteHelper.fetchRoute()` currently discards OSRM's
   route-level `distance` (meters) and `duration` (seconds), returning only
   `List<GeoPoint>?`. Proposed: introduce a `RouteResult(points, distanceMeters,
   durationSeconds)` data class and return that instead. Affects both apps'
   RouteHelper/OsrmRouteHelper and the ViewModel call sites built this session.
2. **Staleness**: store `routeDistanceMeters` / `routeDurationSeconds` alongside
   `routePoints` / `cachedDestLat/Lng`, updated together on OSRM success — same
   freshness window as the polyline (refreshes ~every 50m of movement).
3. **Open question**: when OSRM has never succeeded for the current leg (pure
   straight-line fallback, no road-route cache) — show straight-line distance via
   the existing `RouteHelper.distanceMeters()`, and either omit the time estimate
   or use a rough average-speed-based estimate. Needs a decision before coding.
4. **Formatting (proposed, confirm)**: distance as meters when <1km (e.g. "650 m"),
   km with 1 decimal otherwise (e.g. "3.2 km"); time as minutes when <60 (e.g.
   "8 min"), hours+minutes otherwise (e.g. "1h 15m").

Sequencing: implement for **driver app** first (`Activetripviewmodel.kt` +
`Activetripscreen.kt`), then carry the same pattern to the rider app.

---

## 🟠 Next Up — Rider app: repeat the route/marker pass

Apply the same set of changes just completed for the driver app to the rider app
(`Antar`):

- `ActiveTripViewModel.kt` (rider): same cache/cooldown/destination-binding
  redesign for `OsrmRouteHelper` calls, plus the immediate-route-on-load fix.
  Rider already has the `combine(_driverLocation, _trip)` fix from a prior
  session — check whether destination-binding is *also* needed there for
  pickup→dropoff leg transitions.
- `pinDrawable()` for rider's pickup/dropoff/"Tujuan" markers in `HomeScreen.kt`
  and `ActiveTripScreen.kt`. Rider's driver-position and own-location markers
  stay `circleDrawable`.
- Distance/ETA info card (same design as driver app, once finalized above).

---

## Fix Priority Order — carried over, unaddressed

### 🟠 Medium — Crashes on specific devices/conditions

**NOTIF-DEEPLINK (Driver app)**
When the driver app is backgrounded but not killed, FCM notification taps
(new_trip, offer_accepted) don't navigate anywhere — the intent extra is ignored.
- File: `DriverAntar/ui/.../MainActivity.kt` → `onNewIntent()`
- Fix: add a `handleFcmIntent(intent)` helper and call it from `onNewIntent()`,
  same pattern as `Antar/MainActivity.kt`
- Note: cold-start taps work fine — only backgrounded-app taps are broken

**KEYSTORE-CRASH (Driver app, Android 9)**
`SessionManager.init()` calls `EncryptedSharedPreferences.create()` with no
try-catch. If the hardware KeyStore is invalidated (lock screen type change,
first lock screen setup on a new account), it throws `GeneralSecurityException`
and the app crashes permanently on every cold start until reinstalled.
- File: `DriverAntar/.../utils/Sessionmanager.kt` → `init()`
- Fix: wrap in try-catch; on failure, delete the prefs file and recreate with a
  fresh key (forces re-login); follow the documented migration note already in
  that file

---

### 🟡 Low — Silent errors / best practice

**LOCALE-DATE-PARSE (Driver app `EarningsScreen.kt`)**
`dayAbbrev()` and `dayFullName()` use `Locale.getDefault()` for ISO date parsing
with a `!!` force-unwrap. On devices with Eastern Arabic numeral locales,
`parse()` returns null → NPE silently caught → empty bar chart day labels.
- File: `DriverAntar/.../ui/earnings/Earningsscreen.kt` → `dayAbbrev()`,
  `dayFullName()`
- Fix: change `Locale.getDefault()` to `Locale.US` (pattern already used in
  `TripHistoryScreen.kt` `formatDate()`)

**TOKEN-RACE (Rider app)**
`ApiClient.kt` `TokenAuthenticator` uses `@Volatile isRefreshing` — two
simultaneous 401s can both pass the guard. Driver app correctly uses `Mutex` in
`RetrofitClient.kt`.
- File: `Antar/.../data/remote/Apiclient.kt` → `TokenAuthenticator`
- Fix: replace `@Volatile isRefreshing` + `try/finally` with `Mutex` +
  `withLock`, follow `RetrofitClient.AuthInterceptor` pattern exactly

---

## ⏳ Pending (Do Last)

**DROPOFF-GUARD** — Driver `ActiveTripViewModel.kt`: `canComplete` returns `true`
when `dropoff_lat == 0.0` on transport trips.
> ⚠️ Fix only after verifying `dropoff_lat` is reliably populated in all
> real-device scenarios.

**SEC-6** — Admin routes accept any valid driver/rider JWT. Create `AdminOnly()`
middleware + `admin_users` table.
> ⏳ Defer until admin panel development begins.

---

## Database / Security

- `spatial_ref_sys` RLS disabled — PostGIS system table, removed from scope.
- `trips.status` enum has unused `accepted` value (server uses `agreed`).
  Harmless but worth cleaning up in a future migration.
