# Antar — Master TODO
**Last updated:** June 2026 (race-withdraw fix confirmed; UI latency findings added)
**Paste this file at the start of every session.**

---

## ✅ Candidate-review architecture — fully complete (server + both clients)
See ANTAR_DONE.md for full detail.

---

## ✅ RACE-WITHDRAW fix — confirmed working

Driver app `WaitingForRiderViewModel` and `CounterDecisionViewModel`.
On `withdrawOffer()` HTTP 400, both VMs now call `getActiveTrip()` and map
the authoritative DB state instead of showing a raw error:
- `agreed` → forward to ActiveTrip (rider accepted first in the race)
- no active trip → Rejected (back to IncomingTrips)
- anything else / fetch fails → Error fallback (unchanged)

Symmetric fix: covers both orderings (driver withdraws first OR rider accepts
first) because the race is symmetric — the DB atomic UPDATE decides the winner
and both sides get the same 400 if they lose.

---

## 🟢 Feature: Rider popup UX on driver decline / withdraw — confirmed working

All 8 requirements (R1–R8) verified on-device. See previous session for
full file list and change summary. Move to ANTAR_DONE.md on next session open.

---

## 🟡 UI latency — findings documented, fixes pending

### LAT-1 — First-poll blind window (HIGH)

**Why it matters:** Every polling VM starts its loop with `delay(5_000L)` or
`delay(6_000L)` before the first check. Realtime WebSocket handshake takes
1–3s. Combined worst case: screen shows stale data for up to 8s on entry if
Realtime is slow to connect.

**Affected VMs (both apps):**
- `WaitingForRiderViewModel.startPolling()`
- `SearchingViewModel.startPolling()`
- `NegotiationViewModel.startPolling()`
- `CandidateReviewViewModel.startPolling()`
- `ActiveTripViewModel.startStatusPolling()` (driver + rider)

**Fix direction:** First iteration uses `delay(1_000L)`, subsequent iterations
use the normal interval. No logic change — just a shorter first tick.

**Constraint:** Don't remove the delay entirely — a 0ms first poll races with
`loadTrip()` / `start()` and can trigger duplicate API calls.

---

### LAT-2 — No Realtime on IncomingTripsViewModel (MEDIUM)

**Why it matters:** Driver sees new approved candidate assignments only after
up to 5s poll. `trips` table is already in the Realtime publication. A
subscription filtered by `candidate_driver_id = driverID` would make card
appearance near-instant.

**Files to touch:** `Incomingtripsviewmodel.kt`
**Pattern:** Follow `WaitingForRiderViewModel` Realtime subscription — same
`SupabaseClientHolder`, same timestamp-suffix channel name to avoid reuse
conflicts.
**Constraint:** Subscription must filter on `candidate_driver_id` not just
`id` — the driver doesn't know the trip ID until after the card appears.
Use `FilterOperator.EQ` on `candidate_driver_id`. On any UPDATE received,
call `fetchTrips()` rather than processing the raw record — simpler and
consistent with existing poll logic.

---

### LAT-3 — Realtime no-reconnect on connectivity restore (MEDIUM)

**Why it matters:** `ConnectivityObserver.onAvailable` fires correctly when
network returns but no VM listens to re-subscribe dead Realtime channels.
After a drop+restore, polling continues at 5s intervals but Realtime stays
dead for the session lifetime.

**Affected:** Any screen with a Realtime subscription (WaitingForRider,
CandidateReview, Negotiation, ActiveTrip both apps).

**Fix direction:** Collect `ConnectivityObserver.isOnline` in each affected
VM. On transition from false → true, call `stopWatching()` then
`subscribeRealtime()` to force a clean resubscribe. Polling handles the gap
during reconnect.

**Constraint:** Must debounce — connectivity can flicker rapidly on Talaud.
Only resubscribe after `isOnline` has been true for ≥2s. Use a
`conflatedChannel` or `debounce(2_000)` on the flow.

---

### LAT-4 — MapViewModel trip count badge 15s interval (LOW)

**Why it matters:** FAB badge is the last UI element to update. Easy win.

**Files to touch:** `MapViewModel.kt` — `startTripCountPolling()`
**Fix:** Change `delay(15_000L)` → `delay(8_000L)`. No other change needed.

---

### LAT-5 — Driver location compounding latency (LOW, structural)

**Why it matters:** GPS fires every 3–10s (`LocationService`) + rider polls
`getTrip()` for `driver_lat/lng` every 5s = up to 15s stale driver pin on
rider's map. `RealtimeLocationTracker` already subscribes to `driver_profiles`
changes but only supplements the 5s polling fallback.

**Not blocking:** The `RealtimeLocationTracker` path is already correct — if
Realtime is healthy, latency drops to near-zero. The compounding only applies
when Realtime is down and polling takes over.

**Fix direction (optional):** Reduce `PollingLocationTracker` interval from
5s to 3s to match `LocationService` GPS interval. One-line change in
`Locationtracker.kt`. Trade-off: slightly more battery/network on rider side.

---

## 🔴 Pending — FCM cold-start navigation (deferred)

Consolidated `LaunchedEffect` collector in `Appnavgraph.kt` still does not
navigate to `IncomingTrips` on true cold start from `Map`.

Areas to re-check:
- Emission timing relative to process/Activity recreation
- Whether `currentBackStackEntryFlow` first emission arrives after NavHost
  start destination is composed
- Whether `DeepLinkHandler.emit()` fires before `setContent {}` completes

---

## ⏳ Pending (Do Last)

**SEC-6** — Admin routes accept any valid driver/rider JWT. Defer until admin
panel development begins.

---

## Database / Security (pre-existing, low priority)

- `spatial_ref_sys` RLS disabled — PostGIS system table, out of scope.
- `trips.status` enum has unused `accepted` value (server uses `agreed`).
- `notify_nearest_driver_on_insert()` and `process_trip_notification_timeouts()`
  are `SECURITY DEFINER` callable by `anon`/`authenticated` via PostgREST RPC.
  Worth a `REVOKE EXECUTE` cleanup pass — not a live exploit path.
