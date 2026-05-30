# Antar — TODO List
**Last updated:** May 2026
**Paste alongside ANTAR_CONTEXT.md when working on new features.**

---

## Priority Order

1. [TODO-5] Add `arrived` status — "driver arrived at pickup" flow (server + both apps)
2. [TODO-6] Rider negotiation screen — replace text input with +/- stepper like driver app
3. [TODO-4] Option B — Realtime driver location in rider app *(do when app is stable on production)*

---

## TODO-5 — Add `arrived` status to trip flow

**Why:** Currently the flow jumps agreed → in_progress with no intermediate "driver arrived" state.
The driver has no way to notify the rider they've arrived, and the rider has no feedback.

**New flow:** agreed → arrived → in_progress → completed

### Step 1 — Server: Migration

```sql
ALTER TYPE trip_status ADD VALUE IF NOT EXISTS 'arrived' AFTER 'agreed';
```

### Step 2 — Server: New endpoint `internal/driver/routes.go`

Add inside the auth group:
```go
auth.POST("/trips/:trip_id/arrive", h.ArriveAtPickup)
```

### Step 3 — Server: Handler `internal/driver/handler.go`

```go
func (h *Handler) ArriveAtPickup(c *gin.Context) {
    tripID   := c.Param("trip_id")
    driverID, _ := c.Get("userID")

    var riderID string
    err := h.db.QueryRow(context.Background(),
        `UPDATE trips SET status = 'arrived', updated_at = $1
         WHERE id = $2 AND driver_id = $3 AND status = 'agreed'
         RETURNING rider_id`,
        time.Now(), tripID, driverID,
    ).Scan(&riderID)
    if err != nil {
        response.BadRequest(c, "Trip cannot be marked arrived — must be 'agreed' and assigned to you")
        return
    }

    go func() {
        var riderToken string
        h.db.QueryRow(context.Background(),
            `SELECT COALESCE(fcm_token,'') FROM rider_profiles WHERE id = $1`, riderID,
        ).Scan(&riderToken)
        if riderToken != "" {
            h.fcm.Send(context.Background(), fcm.Message{
                Token: riderToken,
                Notification: &fcm.Notification{
                    Title: "Driver Sudah Tiba! 🚗",
                    Body:  "Driver Anda sudah tiba di lokasi penjemputan",
                },
                Data: map[string]string{
                    "type":    "driver_arrived",
                    "trip_id": tripID,
                },
            })
        }
    }()

    response.Success(c, gin.H{"message": "Marked as arrived"})
}
```

### Step 4 — Server: StartTrip handler — update status check

```go
// In StartTrip, change WHERE clause from:
WHERE id = $2 AND driver_id = $3 AND status = 'agreed'
// To:
WHERE id = $2 AND driver_id = $3 AND status = 'arrived'
```

### Step 5 — Server: `internal/driver/model.go` — add to API reference

Add to `DriverApiService`:
```
POST /trips/:id/arrive  🔒  agreed → arrived; FCM to rider
```

### Step 6 — Driver app: `DriverApiService.kt`

```kotlin
@POST("api/v1/driver/trips/{trip_id}/arrive")
suspend fun arriveAtPickup(
    @Header("Authorization") token: String,
    @Path("trip_id") tripId: String
): Response<ApiResponse<Unit>>
```

### Step 7 — Driver app: `DriverRepository.kt`

```kotlin
suspend fun arriveAtPickup(token: String, tripId: String): Result<Unit> = safeCall {
    api.arriveAtPickup(token, tripId).unwrapVoid()
}
```

### Step 8 — Driver app: `ActiveTripViewModel.kt`

Add new action:
```kotlin
fun arriveAtPickup() {
    viewModelScope.launch {
        uiState = ActiveTripUiState.ActionLoading
        repository.arriveAtPickup(SessionManager.token, tripId)
            .onSuccess { loadTrip() }
            .onFailure { e ->
                uiState = ActiveTripUiState.Error(e.message ?: "Failed to mark arrived")
            }
    }
}
```

### Step 9 — Driver app: `ActiveTripScreen.kt`

Replace `"agreed"` action button block with:
```kotlin
"agreed" -> {
    Button(
        onClick  = { viewModel.arriveAtPickup() },
        enabled  = !isLoading,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        if (isLoading) CircularProgressIndicator(...)
        else Text("Saya Sudah Tiba di Lokasi")
    }
    OutlinedButton(
        onClick  = { viewModel.requestCancel() },
        enabled  = !isLoading,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors   = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text("Batalkan Trip") }
}

"arrived" -> {
    // Green "arrived" status card
    Card(
        colors   = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(12.dp), ...) {
            Icon(Icons.Default.Check, null, tint = Color(0xFF2E7D32))
            Text("Anda sudah tiba — tunggu penumpang naik", color = Color(0xFF2E7D32))
        }
    }
    Button(
        onClick  = { haptic...; viewModel.startTrip() },
        enabled  = !isLoading,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        if (isLoading) CircularProgressIndicator(...)
        else Text("Mulai Perjalanan")
    }
}
```

### Step 10 — Driver app: `ActiveTripScreen.kt` — update TripStatusStepper

Add `arrived` step between agreed and in_progress:
```kotlin
val steps = listOf(
    Triple("agreed",      "Menuju Penumpang",   Icons.Default.Person),
    Triple("arrived",     "Sudah Tiba",         Icons.Default.Check),
    Triple("in_progress", "Dalam Perjalanan",   Icons.Default.DirectionsCar),
    Triple("completed",   "Sampai Tujuan",      Icons.Default.LocationOn),
)
```

### Step 11 — Rider app: `ActiveTripScreen.kt`

Add `"arrived"` to status stepper steps (same as driver above).

Update status message card to handle `"arrived"`:
```kotlin
"arrived" -> Card(
    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
    ...
) {
    Row(...) {
        Icon(Icons.Default.DirectionsCar, tint = Color(0xFF2E7D32))
        Text(
            "Driver sudah tiba! Segera menuju kendaraan",
            color = Color(0xFF2E7D32), fontWeight = FontWeight.SemiBold,
        )
    }
}
```

### Step 12 — Rider app: `SearchingViewModel.kt` and `NegotiationViewModel.kt`

No change needed — `arrived` is after the negotiation phase.

### Step 13 — Both apps: trip recovery

Add `"arrived"` to active trip recovery in:
- Rider `HomeViewModel.checkActiveTrip()`:
  ```kotlin
  "arrived" -> Screen.ActiveTrip.route(tripId)
  ```
- Driver `MapViewModel.recoverActiveTrip()`:
  ```kotlin
  trip.status == "arrived" -> Screen.ActiveTrip.route(trip.id)
  ```

### Step 14 — Rider app: FCM handling

In `RiderFirebaseMessagingService.kt`, add `driver_arrived` type:
```kotlin
"driver_arrived" -> if (tripId.isNotEmpty()) DeepLinkEvent.ToActiveTrip(tripId) else null
```

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
