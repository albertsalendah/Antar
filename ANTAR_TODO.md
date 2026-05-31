# Antar — TODO List
**Last updated:** May 2026
**Paste alongside ANTAR_CONTEXT.md when working on new features.**

---

## Priority Order

1. [TODO-6] Rider negotiation screen — replace text input with +/- stepper like driver app
2. [TODO-4] Option B — Realtime driver location in rider app *(do when app is stable on production)*


---

## TODO-6 — Rider negotiation screen: replace text input with +/- stepper

**Why:** The rider's `NegotiationScreen` uses a plain text input for counter offers.
The driver app uses a +/- stepper with 1000 IDR steps which is much easier on mobile.
Make the rider screen consistent with the driver's `CounterDecisionScreen`.

### Changes needed — `NegotiationViewModel.kt` (rider)

Add stepper state alongside existing `counterInput`:
```kotlin
var counterFare     by mutableStateOf(0.0)
var showCounter     by mutableStateOf(false)   // already exists

// Init counterFare when trip loads (default_fare as floor — need to pass from server)
// Set it when showCounter becomes true:
fun openCounter() {
    counterFare  = trip?.offeredFare ?: 0.0   // start at current offer as suggestion
    showCounter  = true
}

fun incrementCounter() { counterFare += 1_000.0 }
fun decrementCounter() {
    // floor = defaultFare from fare_rules — need to expose via TripResponse or separate call
    // For now use offeredFare as soft floor; server will enforce the hard floor
    if (counterFare - 1_000.0 > 0) counterFare -= 1_000.0
}
```

### Changes needed — `NegotiationScreen.kt` (rider)

Replace the `OutlinedTextField` + digit-filter inside the counter card with a stepper row:
```kotlin
// Replace text field with:
Row(
    verticalAlignment     = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.Center,
    modifier              = Modifier.fillMaxWidth()
) {
    FilledTonalButton(
        onClick        = { viewModel.decrementCounter() },
        modifier       = Modifier.size(52.dp),
        contentPadding = PaddingValues(0.dp),
        shape          = RoundedCornerShape(12.dp),
    ) { Text("−", fontSize = 22.sp, fontWeight = FontWeight.Bold) }

    Spacer(Modifier.width(16.dp))

    OutlinedTextField(
        value         = viewModel.counterFare.roundToInt().toString(),
        onValueChange = { v ->
            v.filter { it.isDigit() }.toDoubleOrNull()?.let { viewModel.counterFare = it }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        textStyle = LocalTextStyle.current.copy(
            fontSize   = 24.sp,
            fontWeight = FontWeight.Bold,
            textAlign  = TextAlign.Center,
        ),
        singleLine = true,
        modifier   = Modifier.width(160.dp),
        prefix     = { Text("Rp") },
    )

    Spacer(Modifier.width(16.dp))

    FilledTonalButton(
        onClick        = { viewModel.incrementCounter() },
        modifier       = Modifier.size(52.dp),
        contentPadding = PaddingValues(0.dp),
        shape          = RoundedCornerShape(12.dp),
    ) { Text("+", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
}

Spacer(Modifier.height(4.dp))
Text(
    "Ketuk + / − untuk ubah Rp 1.000",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth(),
    textAlign = TextAlign.Center,
)
```

Update `submitCounter()` to use `counterFare` instead of `counterInput`:
```kotlin
fun submitCounter(tripId: String) {
    val fare = counterFare
    if (fare <= 0) { error = "Masukkan nominal yang valid"; return }
    // rest stays the same, replace counterInput.trim().toDoubleOrNull() with fare
}
```

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
