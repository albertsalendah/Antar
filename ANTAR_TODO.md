# Antar — Master TODO
**Last updated:** June 2026
**Paste this file + ANTAR_CONTEXT.md at the start of every session.**

---

## What Was Done Last Session

- `observeForever` leak in `NavGraph.kt` History→RateDriver → replaced with `StateFlow` + `LaunchedEffect`, using `last_rated_trip` saved state key.
- Missing DB index on `trips.notified_driver_id` → created via migration.
- `search_path` security fix on all 5 DB functions (`refresh_avg_rating`, `notify_nearest_driver_on_insert`, `process_trip_notification_timeouts`, `sync_driver_lat_lng`, `resolve_island_id`).
- `DEEP-1` ✅ — `DeepLinkHandler` buffer increased from 1 to 4.
- `TODO-7` ✅ — `OsrmRouteHelper` + `RouteHelper` updated with `fetchRouteWithFallback` (straight-line fallback) and OSRM failure caching (5-min cooldown).
- Route line not rendering on rider `ActiveTripScreen` ✅ — root cause was `routePoints` missing from `LaunchedEffect` keys; also fixed race condition via `combine(_driverLocation, _trip)` in `ActiveTripViewModel`.
- `PollingLocationTracker` interval reduced from 15s to 5s.
- TLS fix: added `org.conscrypt:conscrypt-android:2.5.2` dependency and `Security.insertProviderAt(Conscrypt.newProvider(), 1)` in `MainActivity.onCreate()` on both apps — fixes OSRM "Handshake failed" on Android 9 (TLSv1.2 only).
- `VIBRATE` permission added to both `AndroidManifest.xml` files — fixes haptic crash on Android 9.

---

## Fix Priority Order

All previously tracked items are resolved. Only deferred items remain.

---

## ⏳ Pending (Do Last)

**DROPOFF-GUARD** — Driver `ActiveTripViewModel.kt`: `canComplete` returns `true` when `dropoff_lat == 0.0` on transport trips.

> ⚠️ Fix only after verifying `dropoff_lat` is reliably populated from the server in all real-device scenarios before enabling the proximity guard.

**SEC-6** — Admin routes use the same `middleware.Auth()` as user routes — any valid driver/rider JWT can call admin endpoints. Create `AdminOnly()` middleware and an `admin_users` table.

> ⏳ Defer until admin panel development begins.

---

## 4. Database / Security

No remaining items. `spatial_ref_sys` removed from scope — PostGIS system table is not part of the application schema.
