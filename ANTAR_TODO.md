# Antar — Master TODO
**Last updated:** June 2026 (candidate-review server side complete — client work next)
**Paste this file at the start of every session.**

---

## ✅ Rider candidate-review architecture — server side fully complete

All 4 endpoints + 2 bundled follow-ons, plus Withdraw Offer and the TripCard
avatar field, are done (see `ANTAR_DONE.md` for full detail):
- `rider/handler_candidate.go` (new) — approve-candidate, reject-candidate,
  rejected-drivers, reselect-driver
- `rider/routes.go` / `rider/model.go` — routes + request/response structs
- `rider/handler_trips.go` — `tripSelect`/`scanTrip` extended with candidate
  fields (flows through GetActiveTrip/ListTrips/GetTrip automatically)
- `driver/handler_trips.go` / `driver/model.go` — `IncomingTrips` filtered
  by `candidate_driver_id`/`candidate_approved`, plus `candidate_approved_at`
  and `rider_avatar_url` on the response
- `driver/handler_trips.go` / `driver/routes.go` — new `WithdrawOffer`
  endpoint (`POST /driver/trips/:id/withdraw-offer`), auto-reassigns next
  candidate via the same matching SQL as `reject-candidate` — **flagged for
  Richard's confirmation**, this auto-reassign extends slightly beyond the
  original TODO note, retest before relying on it client-side

**Server side for this feature is done. Everything left below is client-only.**

---

## ✅ Rider app pass — complete

All four rider-app items from the planned pass are done (see `ANTAR_DONE.md`):
TOKEN-RACE fix, teardrop pin markers, route caching/cooldown redesign moved
into `Activetripviewmodel.kt`, distance/ETA info card.

## ✅ Driver app — bug fix + UX pass — complete

NEGOT-RECOVER fixed, badge on "Lihat Perjalanan" FAB applied and confirmed.

## ✅ UI polish — padding & sheet height — applied and confirmed

---

## 🟡 Client — driver TripCard avatar rendering

Server already returns `rider_avatar_url` on `IncomingTrips`. Remaining work
is Kotlin-only: `IncomingTripResponse` model field + `AsyncImage` rendering
in `TripCard` (same Coil pattern as `Profilescreen.kt`). `rider_name`
already exists on the model but is currently unused in the card UI.

---

## 🔵 Client-only — server already supports these

- **Driver `TripCard` countdown** — `candidate_approved_at` already
  returned; client-side `LaunchedEffect` ticking every second, card
  fades/removes at zero
- **Rider new screens/routes** (`Screen.kt`): `CandidateReview/{tripId}`,
  `NoDriverFound/{tripId}`, `RejectedDriverList/{tripId}`. Navigation:
  `requestRide` success → directly to `CandidateReview` (decision #7).
  `CandidateReview` needs a Realtime subscription on the trip row for
  `candidate_driver_id` changes (new candidate) or `status='offered'`
  (driver responded → navigate to Negotiation)
- **Rider `CandidateReview` screen UI** — driver avatar/name/vehicle/rating
  (adapt DriverAntar's `RatingBar`), Accept/Reject buttons calling
  `approve-candidate`/`reject-candidate`. After Accept: hide buttons, show
  countdown copy
- **Rider driver non-response popup** — client-side, triggered when rider's
  own 3-min countdown hits zero (decision #1). Buttons: "Cari Driver
  Berikutnya (30)" (30s auto-confirm → `reject-candidate`) and "Batalkan
  Pencarian" (cancels trip entirely)
- **Rider `RejectedDriverList` screen** — shown only on "No Driver Found"
  (decision #8); calls existing `GET rejected-drivers` to populate, available
  entries selectable, unavailable greyed out; selection calls
  `reselect-driver`
- **Driver Withdraw Offer wiring** — server endpoint
  (`POST /driver/trips/:id/withdraw-offer`) is live. `CounterDecisionViewModel.rejectAndReset()`
  and a new Withdraw button on `WaitingForRiderScreen` both need to call it

---

## 🔴 Pending — FCM cold-start navigation (deferred again, root cause not fully found)

Consolidated the two competing `LaunchedEffect` collectors in
`Appnavgraph.kt` into one. **Retested — still does NOT navigate to
`IncomingTrips` on a true cold start from `Map`.** Needs fresh
investigation, don't assume the existing analysis is complete. Likely areas
to re-check: emission timing in `MainActivity.onCreate`/`onNewIntent`
relative to process/Activity recreation on cold start, and whether
`currentBackStackEntryFlow`'s first emission is guaranteed to occur after
`NavHost`'s start destination is composed (vs. just being non-null).

---

## 🟢 Driver — decline a trip request (code delivered, confirm + move to DONE)

Client-side only. `Incomingtripsviewmodel.kt` `declinedTripIds` set +
`declineTrip(tripId)`; `Incomingtripsscreen.kt` dismiss `IconButton`.
**Richard: confirm this is applied and working, then move to `ANTAR_DONE.md`.**

---

## ⏳ Pending (Do Last)

**SEC-6** — Admin routes accept any valid driver/rider JWT. Create
`AdminOnly()` middleware + `admin_users` table.
> ⏳ Defer until admin panel development begins.

---

## Database / Security

- `spatial_ref_sys` RLS disabled — PostGIS system table, removed from scope.
- `trips.status` enum has unused `accepted` value (server uses `agreed`).
  Harmless but worth cleaning up in a future migration.
- `notify_nearest_driver_on_insert()` and `process_trip_notification_timeouts()`
  are `SECURITY DEFINER` and callable directly via PostgREST RPC by
  `anon`/`authenticated` roles (pre-existing, confirmed via `get_advisors`).
  Worth a future `REVOKE EXECUTE FROM anon, authenticated` cleanup pass —
  low priority, not a live exploit path.
