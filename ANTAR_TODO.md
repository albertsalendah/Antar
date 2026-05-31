# Antar — TODO List
**Last updated:** May 2026
**Paste alongside ANTAR_CONTEXT.md when working on new features.**

---

## Priority Order

1. [TODO-4] Option B — Realtime driver location in rider app *(do when app is stable on production)*

---

## TODO-4 — Option B: Realtime Driver Location in Rider App

**Do this after the app is stable on production.**
**Prerequisites:** TODO-2 is done (last_lat/last_lng now written by server on every location update).**

### Step 1 — Supabase SQL (one migration)
```sql
CREATE POLICY "riders can read driver location"
ON driver_profiles FOR SELECT TO authenticated USING (true);
```
Add `driver_profiles` to Realtime publication via Supabase Dashboard → Database → Replication.

### Step 2 — Implement `RealtimeLocationTracker` in rider `LocationTracker.kt`

Replace the stub `RealtimeLocationTracker` class:
```kotlin
class RealtimeLocationTracker(
    private val api: ApiService,
    private val supabase: SupabaseClient,
    private val driverProfileId: String,
) : LocationTracker {
    private val pollingFallback = PollingLocationTracker(api, intervalMs = 15_000L)
    private val _location       = MutableStateFlow<DriverLocation?>(null)
    override val location: StateFlow<DriverLocation?> = _location.asStateFlow()

    override fun start(tripId: String, scope: CoroutineScope) {
        pollingFallback.start(tripId, scope)
        scope.launch {
            runCatching {
                val ch = supabase.channel("driver-loc-$driverProfileId-${System.currentTimeMillis()}")
                ch.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "driver_profiles"
                    filter("id", FilterOperator.EQ, driverProfileId)
                }.onEach { action ->
                    val lat = action.record["last_lat"]?.jsonPrimitive?.doubleOrNull ?: return@onEach
                    val lng = action.record["last_lng"]?.jsonPrimitive?.doubleOrNull ?: return@onEach
                    _location.value = DriverLocation(lat, lng)
                }.launchIn(scope)
                ch.subscribe()
            }
        }
    }

    override fun stop() { pollingFallback.stop() }
}
```

### Step 3 — Update `ActiveTripViewModel` (rider) — change one line

```kotlin
// BEFORE
private val locationTracker: LocationTracker = PollingLocationTracker(api, intervalMs = 3_000L)

// AFTER
private val locationTracker: LocationTracker = RealtimeLocationTracker(
    api             = api,
    supabase        = supabase,
    driverProfileId = trip?.driverId ?: ""
)
```
