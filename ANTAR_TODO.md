# Antar — Master TODO
**Last updated:** June 2026 (candidate-review architecture session)
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

## 🟣 Rider candidate-review / driver-approval architecture (DB done — server + both apps pending)

Major architectural shift, larger than originally scoped. Replaces the old
"auto-notify nearest driver" flow with: rider reviews a candidate driver
(name/photo/vehicle/rating) and explicitly approves before the driver is
notified. If the driver doesn't respond in 3 minutes, rider is offered the
next candidate via a client-side popup (not cron-timing-dependent).

**Locked design decisions:**
1. **Driver non-response popup** — triggered client-side the instant the
   rider's own 3-min countdown hits zero (not dependent on pg_cron, to avoid
   up-to-1-min UX delay mismatch). Text: "Driver tidak merespons / Driver
   yang dipilih tidak merespons dalam waktu yang ditentukan." Buttons: "Cari
   Driver Berikutnya (30)" (30s auto-confirm countdown → calls
   reject-candidate) and "Batalkan Pencarian" (cancels the trip entirely —
   rider must re-request from scratch, confirmed).
2. **Validate-at-approval** — when rider taps Accept, server re-checks driver
   is still online AND not on another trip; distinct error messages per
   failure type ("driver is offline" vs "driver is on another trip").
3. **`notification_attempts`** counts ONLY driver non-responses, never rider
   rejections. Threshold lives in `app_settings.max_driver_notification_attempts`
   (admin-panel configurable — confirmed not a client-side setting), currently
   `8`. Separate soft exclusion-list safety cap of `10` (prevents a rider
   rejecting indefinitely).
4. **Post-approval flow** unchanged — driver still goes through the existing
   offer/negotiation path; approval only unlocks visibility/notification.
5. **Trip cancellation** — rider taps "Cancel" explicitly, OR auto-cancels if
   rider abandons/closes the "No Driver Found" screen. 30-min safety-net
   auto-cancel also added in cron for fully abandoned sessions.
6. **No rider-side timeout on the review step itself** — rider can take as
   long as they want deciding whether to approve a shown candidate.
7. **Skip the "Finding a driver..." loader** — navigate directly from
   `requestRide` success to `CandidateReview`, which owns its own
   loading/null-candidate state (consistent with the rest of the app).
8. **Rejected-driver recovery list** — shown on "No Driver Found" only if ≥1
   previously-rejected driver is currently available (online + not in active
   trip). Unavailable ones shown greyed-out, not removed. Selecting one
   re-validates availability (race guard), removes from exclusions, and
   auto-sets `candidate_approved=true` immediately (no second review step —
   rider already chose), firing FCM right away.

**✅ Done this session — DB migration `candidate_review_and_driver_exclusions`
applied to project `lbiijuuugqgcfrpksilh`.** See `ANTAR_DONE.md` for full
contents. Summary: new `trips` columns (`candidate_driver_id`,
`candidate_approved`, `candidate_approved_at`), new `trip_driver_exclusions`
table, `app_settings.max_driver_notification_attempts=8`,
`notify_nearest_driver_on_insert()` and `process_trip_notification_timeouts()`
both rewritten for the candidate flow. Post-migration verification passed
(columns/table/setting confirmed, `search_path` intact on both functions).

**⏳ Remaining implementation — in suggested order:**

1. **4 new server endpoints** (Go, `Antar-Server/internal/rider/`):
   - `POST /rider/trips/:id/approve-candidate` — validate-at-approval (online +
     not-in-trip checks, specific errors), set `candidate_approved=true` +
     `candidate_approved_at=now()`, fire FCM.
   - `POST /rider/trips/:id/reject-candidate` — add to
     `trip_driver_exclusions`, find next nearest eligible driver, set as new
     candidate (`approved=false`) or null + `no_drivers_found:true`.
   - `GET /rider/trips/:id/rejected-drivers` — exclusion list joined with
     `driver_profiles`, each with computed `is_available`.
   - `POST /rider/trips/:id/reselect-driver` `{driver_id}` — re-validate
     availability (race guard), remove from exclusions, auto-approve
     (`candidate_approved=true`, `candidate_approved_at=now()`), fire FCM.
2. **`IncomingTrips` query change** (driver app/server) — filter to
   `status='requested' AND candidate_driver_id=me AND candidate_approved=true`.
   Add `candidate_approved_at` to `IncomingTripResponse` for the client-side
   countdown.
3. **Driver `TripCard` avatar** — `rp.avatar_url` join needed in the
   `IncomingTrips` query (`Antar-Server/internal/driver/handler_trips.go`),
   new field on Go struct + Kotlin `IncomingTripResponse`, `AsyncImage`
   rendering (same Coil pattern as `Profilescreen.kt`). `rider_name` already
   exists on the model but is currently unused in the card UI.
4. **Driver `TripCard` countdown** — circular countdown based on
   `candidate_approved_at + 3min`, client-side `LaunchedEffect` ticking every
   second, card fades/removes at zero.
5. **Rider new screens/routes** (`Screen.kt`): `CandidateReview/{tripId}`,
   `NoDriverFound/{tripId}`, `RejectedDriverList/{tripId}`. Navigation:
   `requestRide` success → directly to `CandidateReview` (no intermediate
   Searching screen, per decision #7 above). `CandidateReview` needs a
   Realtime subscription watching the trip row for `candidate_driver_id`
   changes (new candidate) or `status='offered'` (driver responded → navigate
   to Negotiation).
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
- **New this session:** `notify_nearest_driver_on_insert()` and
  `process_trip_notification_timeouts()` are `SECURITY DEFINER` and callable
  directly via PostgREST RPC by `anon`/`authenticated` roles (pre-existing,
  not introduced by this session's rewrite — confirmed via `get_advisors`).
  Worth a future `REVOKE EXECUTE FROM anon, authenticated` cleanup pass since
  neither should ever be called directly by a client. Low priority — not a
  live exploit path since both functions only read/derive from trigger
  context (`NEW`) or scan all trips, not attacker-controlled input, but still
  unnecessary surface.
