# Antar — Master TODO
**Last updated:** June 2026 (current session)
**Paste this file at the start of every session.**

---

## ✅ Rider app pass — complete

All four rider-app items from the planned pass are done (see `ANTAR_DONE.md`):

- TOKEN-RACE fixed — `Apiclient.kt` `TokenAuthenticator` now uses `Mutex` +
  `withLock`, same pattern as driver's `AuthInterceptor`.
- Teardrop `pinDrawable()` markers for pickup/dropoff in `Homescreen.kt` and
  `Activetripscreen.kt`. Driver/user position markers stay `circleDrawable`.
- Route caching/cooldown/destination-binding redesign moved entirely into
  `Activetripviewmodel.kt` (`cachedDestLat/Lng`, `lastOsrmFailureMs`, 5-min
  `OSRM_COOLDOWN_MS`) — mirrors driver's design exactly.
- Distance/ETA info card on `Activetripscreen.kt` — `OsrmRouteHelper.fetchRoute()`
  now returns `RouteResult(points, distanceMeters, durationSeconds)`;
  `fetchRouteWithFallback` removed, fallback logic now lives in the ViewModel.

## ✅ Driver app — bug fix + UX pass

- **NEGOT-RECOVER fixed** — `MapViewModel.recoverActiveTrip()` previously sent
  the driver to `IncomingTrips` when recovering a trip where the rider had
  countered (`status=offered`, `last_offer_by=rider`). Now correctly routes to
  `CounterDecisionScreen` with the real `driver_counter_count` from the trip.
- **Badge on "Lihat Perjalanan" FAB — applied and confirmed.** `MapViewModel`
  exposes `incomingTripCount`, polled every 15s via `getIncomingTrips()` while
  online; `MapScreen.kt` `BadgedBox` wrapper applied.

## ✅ UI polish — padding & sheet height — applied and confirmed

- FAB-to-sheet gap reduced in `Homescreen.kt`, rider `Activetripscreen.kt`,
  driver `Activetripscreen.kt`.
- `minSheetHeight` increased on both ActiveTrip screens (rider + driver).

---

## 🔴 Pending — FCM cold-start navigation (deferred for later)

Root cause confirmed: tapping a `new_trip` / `offer_accepted` notification only
navigates correctly to `IncomingTrips`/`ActiveTrip` when that screen is already
in the saved back stack. On a true cold start from `Map`, navigation silently
fails — `LaunchedEffect(deepLinkRoute)` in `AppNavGraph` fires before NavHost
has initialised its first destination.

Fix designed but not yet applied/retested:
- `DeepLinkHandler.kt` (driver) — route cold-start emissions through it too
  (currently only used for the backgrounded-not-killed path).
- `MainActivity.kt` — emit to `DeepLinkHandler` in `onCreate()` instead of
  passing `deepLinkRoute` directly to `AppNavGraph`.
- `AppNavGraph.kt` — remove the `deepLinkRoute` param + its `LaunchedEffect`;
  in the existing `DeepLinkHandler.events.collect` block, wait on
  `navController.currentBackStackEntryFlow.first { it != null }` before
  navigating, then call `DeepLinkHandler.consume()`.

When resumed: apply all three files together, then retest a notification tap
from a fully killed state where the last screen was `Map` (not
`IncomingTrips`) — confirm on Android 11 and at least one other version.

---

## 🔵 Discussed, design pending — next session

These were raised but intentionally deferred for a dedicated design pass:

1. **Rider — driver review/accept screen.** Instead of auto-assigning the
   first offer, show the rider a profile card (photo, name, phone, vehicle,
   rating) with Accept/Reject before moving to Negotiation. Reject reuses the
   existing `POST /trips/:id/reject` (resets to `requested`, no new endpoint).
   Needs `TripResponse` (rider's `tripSelect` in
   `Antar-Server/internal/rider/handler_trips.go`) enriched with driver
   avatar/vehicle/rating columns — join `driver_vehicles` + driver rating
   fields. New screen: `Antar/.../ui/trip/` between Searching and Negotiation.
2. **Driver — decline a trip request.** Client-side only for v1: add a
   decline action on the `IncomingTrips` trip card, track declined IDs in an
   in-memory `Set<String>` in `IncomingTripsViewModel`, filter from the polled
   list. Resets on app kill — acceptable for v1. No server change.
3. **Suggested pairing with #2** — "Withdraw Offer" button on
   `WaitingForRiderScreen` (driver) calling the existing cancel endpoint, so a
   driver isn't stuck waiting indefinitely on a slow rider.

---

## Fix Priority Order — carried over, unaddressed

### 🟡 Low — Silent errors / best practice

*(LOCALE-DATE-PARSE and DROPOFF-GUARD completed last session — see
ANTAR_DONE.md. Nothing currently in this bucket.)*

---

## ⏳ Pending (Do Last)

**SEC-6** — Admin routes accept any valid driver/rider JWT. Create `AdminOnly()`
middleware + `admin_users` table.
> ⏳ Defer until admin panel development begins.

---

## Database / Security

- `spatial_ref_sys` RLS disabled — PostGIS system table, removed from scope.
- `trips.status` enum has unused `accepted` value (server uses `agreed`).
  Harmless but worth cleaning up in a future migration.
