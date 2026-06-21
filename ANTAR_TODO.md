# Antar — Master TODO
**Last updated:** June 2026 (candidate-review server-endpoint planning session)
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

## 🟢 Driver — decline a trip request (code delivered, confirm + move to DONE)

Client-side only, no server change. Code given for:
- `Incomingtripsviewmodel.kt` — `declinedTripIds` set, filtered in `fetchTrips`,
  `declineTrip(tripId)`.
- `Incomingtripsscreen.kt` — `Icons.Default.Close` dismiss `IconButton` wired
  into `TripCard`.

**Richard: confirm this is applied and working, then it moves to `ANTAR_DONE.md`.**

---

## 🔴 Pending — FCM cold-start navigation (deferred again, root cause not fully found)

Consolidated the two competing `LaunchedEffect` collectors in `Appnavgraph.kt`
into one (waits on `navController.currentBackStackEntryFlow.first { true }`
before navigating, then calls `DeepLinkHandler.consume()`). Also cleaned up a
stale docstring in `DeepLinkHandler.kt`.

**Retested — still does NOT navigate to `IncomingTrips` on a true cold start
from `Map`.** The duplicate-collector race was real and worth fixing, but it
was not the (or not the only) root cause. Needs fresh investigation next time
this is picked up — don't assume the existing analysis is complete. Likely
areas to re-check: emission timing in `MainActivity.onCreate`/`onNewIntent`
relative to process/Activity recreation on cold start, and whether
`currentBackStackEntryFlow`'s first emission is actually guaranteed to occur
after `NavHost`'s start destination is composed (vs. just being non-null).

---

## 🟣 Rider candidate-review / driver-approval architecture (DB done — server endpoints next, design locked)

Major architectural shift, larger than originally scoped. Replaces the old
"auto-notify nearest driver" flow with: rider reviews a candidate driver
(name/photo/vehicle/rating) and explicitly approves before the driver is
notified. If the driver doesn't respond in 3 minutes, rider is offered the
next candidate via a client-side popup (not cron-timing-dependent).

**Locked design decisions (unchanged from prior sessions):**
1. Driver non-response popup triggered client-side when rider's own 3-min
   countdown hits zero. Buttons: "Cari Driver Berikutnya (30)" (30s
   auto-confirm → reject-candidate) and "Batalkan Pencarian" (cancels trip
   entirely, rider must re-request).
2. Validate-at-approval — server re-checks driver is online AND not on
   another trip when rider taps Accept; distinct error per failure type.
3. `notification_attempts` counts ONLY driver non-responses (cron timeouts),
   never rider rejections. Threshold lives in
   `app_settings.max_driver_notification_attempts` (admin-panel
   configurable), currently `8`. Exclusion-list soft cap `10`.
4. Post-approval flow unchanged — driver still goes through existing
   offer/negotiation path.
5. Trip cancellation — explicit rider cancel, or abandon → 30-min cron
   safety-net auto-cancel.
6. No rider-side timeout on the review step itself.
7. Skip "Finding a driver..." loader — `requestRide` success navigates
   straight to `CandidateReview`.
8. Rejected-driver recovery list shown on "No Driver Found" only — selecting
   one re-validates availability and auto-approves (no second review step).

**✅ DB migration `candidate_review_and_driver_exclusions` applied** to
project `lbiijuuugqgcfrpksilh` (see `ANTAR_DONE.md` for full contents).

**✅ This session — server endpoint design finalized.** Verified live function
bodies for `notify_nearest_driver_on_insert()` and
`process_trip_notification_timeouts()` via `pg_get_functiondef` (not just
prose descriptions) so the new Go endpoints mirror the exact same matching
criteria (island + vehicle_type + online + `ST_DWithin` radius, excluding
`trip_driver_exclusions` and drivers already on an active trip via
`driver_id OR offered_by`). Confirmed current `rider/model.go` and
`rider/routes.go` against GitHub match what's already known — no drift.
Resolved 7 open design questions with Richard (below) — ready to implement
next session, no further discussion needed before coding.

### Finalized plan — 4 server endpoints + 2 bundled follow-ons

**New file:** `Antar-Server/internal/rider/handler_candidate.go` (keeps
`handler_trips.go` from growing further). Edits also needed in
`rider/routes.go` (register 4 routes in the existing protected group, same
pattern as other `/trips/:trip_id/*` routes) and `rider/model.go` (request/
response structs for the 4 endpoints).

**1. POST `/rider/trips/:id/approve-candidate`**
- Validate driver `is_online`; validate not on another active trip — same
  `NOT EXISTS` check the trigger uses (`driver_id OR offered_by`, status IN
  offered/agreed/arrived/in_progress). Distinct error message per failure.
- Race-guard: confirm `trips.candidate_driver_id` still equals the driver
  being approved before committing (cron may have reassigned mid-request).
- On success: `candidate_approved=true`, `candidate_approved_at=now()`.
- FCM: reuse existing `"new_trip"` data type/payload (same as
  `pkg/notification/processor.go`'s queue-based push) — routes driver to
  IncomingTrips.

**2. POST `/rider/trips/:id/reject-candidate`**
- Insert row into `trip_driver_exclusions`.
- Find next nearest eligible driver: **duplicate the matching SQL inline**
  in this handler (Option A — same pattern as `notify_nearest_driver_on_insert()`,
  no new migration or shared DB function). If none found, set
  `candidate_driver_id=null` (rider screen shows "No Driver Found").
- Gotcha: do **NOT** increment `notification_attempts` here — that counter
  is reserved for cron-driven non-response timeouts only (locked decision #3).

**3. GET `/rider/trips/:id/rejected-drivers`**
- Join `trip_driver_exclusions` with `driver_profiles` (+ active vehicle for
  type, + rating fields).
- Response per driver: `driver_id, full_name, avatar_url, vehicle_type,
  avg_rating, rating_count, is_available`.
- `is_available` = online AND active vehicle's type matches trip's
  `vehicle_type_id` AND not on another active trip (same check as #1).

**4. POST `/rider/trips/:id/reselect-driver` `{driver_id}`**
- Only callable when `trips.candidate_driver_id IS NULL` (matches locked
  decision #8 — UI only shows this list on "No Driver Found").
- Re-validate availability (race guard — driver may have gone offline/busy
  since the list was fetched).
- On success: delete the specific `trip_driver_exclusions` row for
  `(trip_id, driver_id)`, set as new candidate with
  `candidate_approved=true` + `candidate_approved_at=now()` immediately (no
  second review step). Fire same FCM as #1.

**Bundled follow-on A — extend `TripResponse` with candidate fields**
- Files: `handler_trips.go` (`tripSelect` const + `scanTrip` func, shared by
  GetActiveTrip/GetTrip/ListTrips), `model.go` (`TripResponse` struct).
- Add a second join to `driver_profiles` for `candidate_driver_id` (existing
  `dp` alias already joins on `driver_id` — needs its own alias).
- New fields: `candidate_driver_id`, `candidate_approved`,
  `candidate_approved_at`, `candidate_driver_name`,
  `candidate_driver_avatar_url`, `candidate_vehicle_type`,
  `candidate_driver_rating`, `notification_attempts`.
- Why: lets the future `CandidateReview` screen render via the existing
  `GetActiveTrip` endpoint — no new read endpoint needed.

**Bundled follow-on B — `IncomingTrips` query filter (was separate TODO item,
now folded into this same pass per Richard's confirmation)**
- File: `Antar-Server/internal/driver/handler_trips.go`, `IncomingTrips`
  handler.
- Add `candidate_driver_id = <me> AND candidate_approved = true` to the
  WHERE clause, alongside the existing island_id + vehicle_type_id filters
  (don't drop those — island isolation still applies).
- Add `candidate_approved_at` to `IncomingTripResponse` (Go struct +
  Kotlin `IncomingTripResponse` model) — needed for the driver-side
  countdown UI (still a separate pending item below).
- Why bundled now: without this, the FCM fired by approve-candidate (#1)
  reaches the driver but the trip won't show up filtered correctly in
  IncomingTrips — the two need to ship together to be testable.

### Still pending after the above (unchanged, do in this order)

3. **Driver `TripCard` avatar** — `rp.avatar_url` join needed in the
   `IncomingTrips` query (same file as follow-on B above), new field on Go
   struct + Kotlin `IncomingTripResponse`, `AsyncImage` rendering (same Coil
   pattern as `Profilescreen.kt`). `rider_name` already exists on the model
   but is currently unused in the card UI.
4. **Driver `TripCard` countdown** — circular countdown based on
   `candidate_approved_at + 3min`, client-side `LaunchedEffect` ticking every
   second, card fades/removes at zero.
5. **Rider new screens/routes** (`Screen.kt`): `CandidateReview/{tripId}`,
   `NoDriverFound/{tripId}`, `RejectedDriverList/{tripId}`. Navigation:
   `requestRide` success → directly to `CandidateReview` (decision #7).
   `CandidateReview` needs a Realtime subscription watching the trip row for
   `candidate_driver_id` changes (new candidate) or `status='offered'`
   (driver responded → navigate to Negotiation).
6. **Rider `CandidateReview` screen UI** — driver avatar (`AsyncImage`), name,
   vehicle type, rating (adapt DriverAntar's `RatingBar` component for the
   rider app), Accept/Reject buttons. After Accept: hide buttons, show
   "Driver ini akan kami tunggu selama X menit" + countdown.
7. **Rider driver non-response popup** — client-side, per decision #1 above.
   Indonesian copy is locked (see above); 30s auto-confirm on "Cari Driver
   Berikutnya".
8. **Rider `RejectedDriverList` screen** — only shown per decision #8 above;
   available entries selectable, unavailable greyed-out/disabled; selecting
   calls `reselect-driver`.
9. **Withdraw Offer / driver-reject endpoint** — folds in the previously
   separate item. Root cause: existing `CancelTrip` endpoint
   (`Antar-Server/internal/driver/handler_trips.go`) filters
   `WHERE driver_id = $3 AND status = 'agreed'`, but `driver_id` is null until
   the rider accepts — silently fails (`RowsAffected=0`) for `offered`-status
   flows. This likely means the existing "Tolak & Batalkan" button in
   `CounterDecisionScreen` is *already* broken in production today, not just
   the new Withdraw button. **Decision: dedicated endpoint** (Option 1,
   mirrors rider's `RejectOffer`) — resets trip to `requested`, clears
   `offered_by`/`offered_fare`/`last_offer_by`, zeros counters. Both
   `CounterDecisionViewModel.rejectAndReset()` and the new Withdraw button on
   `WaitingForRiderScreen` call it. Rider does not need to re-request — same
   trip ID, `NegotiationViewModel` already flips to `requested` →
   `SearchingScreen` automatically. Uses the same `trip_driver_exclusions`
   mechanism now in place.

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
- `notify_nearest_driver_on_insert()` and `process_trip_notification_timeouts()`
  are `SECURITY DEFINER` and callable directly via PostgREST RPC by
  `anon`/`authenticated` roles (pre-existing, not introduced by the
  candidate-review migration — confirmed via `get_advisors`). Worth a future
  `REVOKE EXECUTE FROM anon, authenticated` cleanup pass since neither should
  ever be called directly by a client. Low priority — not a live exploit path
  since both functions only read/derive from trigger context (`NEW`) or scan
  all trips, not attacker-controlled input, but still unnecessary surface.
