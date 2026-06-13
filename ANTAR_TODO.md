# Antar — Master TODO
**Last updated:** June 2026 (current session)
**Paste this file at the start of every session.**

---

## ✅ Driver app pass — complete

All driver-app route/marker/distance-ETA/reliability work for this round is
done (see `ANTAR_DONE.md` for full detail). Summary of what just landed:

- **Distance/ETA display** on `ActiveTripScreen` (driver) — top-left info card,
  shown for `agreed`/`in_progress`, hidden on `arrived`. Design now FINAL:
  - `RouteHelper.fetchRoute()` returns `RouteResult(points, distanceMeters,
    durationSeconds)` instead of `List<GeoPoint>?`, parsed from OSRM's
    top-level `distance`/`duration`.
  - `ActiveTripViewModel` exposes `routeDistanceMeters` / `routeDurationSeconds`,
    updated together with `routePoints` (same staleness window, ~50m movement);
    `startTrip()` resets all three together.
  - Straight-line fallback: distance via `RouteHelper.distanceMeters()`
    (Haversine), duration omitted (`null`) — no average-speed guess.
  - Formatting: meters <1km (e.g. "650 m"), km with 1 decimal otherwise
    (e.g. "3.2 km"); minutes <60 (e.g. "8 min"), else "1h 15m".
- **NOTIF-DEEPLINK fixed** — new `ui/navigation/DeepLinkHandler.kt` (driver,
  same pattern as rider's DEEP-1); `MainActivity.onNewIntent()` emits,
  `AppNavGraph` collects and navigates when logged in. Cold-start path
  (existing `deepLinkRoute` param) unchanged.
- **KEYSTORE-CRASH fixed** — `SessionManager.init()` (driver) wraps
  `EncryptedSharedPreferences.create()` in try/catch; on failure wipes the
  stale prefs file + `MasterKey.DEFAULT_MASTER_KEY_ALIAS` from
  `AndroidKeyStore` and recreates with a fresh key (forces one-time re-login).

### Earlier completed (prior session, driver route/marker pass)
- Route caching/cooldown/destination-binding redesign lives entirely in
  `Activetripviewmodel.kt` (`cachedDestLat/Lng`, `lastOsrmFailureMs`, 5-min
  `OSRM_COOLDOWN_MS`); immediate route draw on `loadTrip()`; `startTrip()`
  resets cache for the dropoff leg.
- Teardrop `pinDrawable()` markers for pickup/dropoff/"Tujuan"; driver's own
  position marker stays `circleDrawable`.
- CONSCRYPT-PLACEMENT resolved for both apps — `Security.insertProviderAt`
  moved to Application-level `onCreate()`.

### Earlier completed (background, unchanged)
NavGraph `observeForever` leak fix, missing index on
`trips.notified_driver_id`, `search_path` security fix on 5 DB functions,
DEEP-1 buffer fix (rider), rider route-line race fix
(`combine(_driverLocation, _trip)` + `routePoints` LaunchedEffect key),
`PollingLocationTracker` 15s→5s, VIBRATE permission fix, Conscrypt+OkHttp TLS
fix for rider OSRM calls.

---

## 🔴 Next Up — Rider app pass

Apply the same patterns just finished for the driver app to the rider "Antar"
app. All design decisions below are final (carried over from the driver pass).

1. **Route caching/marker redesign** — `Antar/.../ui/trip/Activetripviewmodel.kt`:
   move cache/cooldown/destination-binding entirely into the ViewModel,
   following driver's `Activetripviewmodel.kt` (`cachedDestLat/Lng`,
   `lastOsrmFailureMs`, `OSRM_COOLDOWN_MS`). Rider already has the
   `combine(_driverLocation, _trip)` fix from a prior session — check whether
   destination-binding is *also* needed for pickup→dropoff leg transitions.
2. **Teardrop `pinDrawable()` markers** for rider's pickup/dropoff/"Tujuan" in
   `HomeScreen.kt` and `ActiveTripScreen.kt`. Driver-position and own-location
   markers stay `circleDrawable` — same scope as driver app.
3. **Distance/ETA info card** — port the now-finalized driver design
   (`RouteResult`, top-left card, formatting rules, straight-line-omits-duration)
   to `Antar/.../ui/trip/Osrmroutehelper.kt` + `Activetripviewmodel.kt` +
   `Activetripscreen.kt`. `OsrmRouteHelper.fetchRoute()` needs the same
   `RouteResult` return-type change as the driver's `RouteHelper`.
4. **TOKEN-RACE** (bundle into this pass — same file area as #1/#3):
   `Antar/.../data/remote/Apiclient.kt` `TokenAuthenticator` uses
   `@Volatile isRefreshing`, letting two simultaneous 401s both pass the guard.
   Replace with `Mutex` + `withLock`, following driver's
   `RetrofitClient.AuthInterceptor` exactly.

---

## Fix Priority Order — carried over, unaddressed

### 🟡 Low — Silent errors / best practice

**LOCALE-DATE-PARSE (Driver app `EarningsScreen.kt`)**
`dayAbbrev()` and `dayFullName()` use `Locale.getDefault()` for ISO date parsing
with a `!!` force-unwrap. On devices with Eastern Arabic numeral locales,
`parse()` returns null → NPE silently caught → empty bar chart day labels.
- File: `DriverAntar/.../ui/earnings/Earningsscreen.kt` → `dayAbbrev()`,
  `dayFullName()`
- Fix: change `Locale.getDefault()` to `Locale.US` (pattern already used in
  `TripHistoryScreen.kt` `formatDate()`)

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
