package com.richard_salendah.antar.ui.home

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.antar.Antar
import com.richard_salendah.antar.data.model.NearbyDriverResponse
import com.richard_salendah.antar.data.model.RequestRideRequest
import com.richard_salendah.antar.data.model.VehicleTypeResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val api     = (app as Antar).apiService
    private val session = (app as Antar).sessionManager

    // ── Rider info ────────────────────────────────────────────────────────────
    var riderName by mutableStateOf("")

    // ── Location ──────────────────────────────────────────────────────────────
    var userLocation     by mutableStateOf<Pair<Double, Double>?>(null)
    var hasInitialCenter by mutableStateOf(false)
        private set

    // ── Nearby drivers ────────────────────────────────────────────────────────
    var nearbyDrivers    by mutableStateOf<List<NearbyDriverResponse>>(emptyList())
    /** vehicleTypeName → count of online drivers of that type */
    var countByType      by mutableStateOf<Map<String, Int>>(emptyMap())

    // ── Vehicle types ─────────────────────────────────────────────────────────
    var vehicleTypes     by mutableStateOf<List<VehicleTypeResponse>>(emptyList())
    var selectedType     by mutableStateOf<VehicleTypeResponse?>(null)

    // ── Trip type ─────────────────────────────────────────────────────────────
    var tripType         by mutableStateOf("transport") // "transport" | "errand"

    // ── Pickup pin ────────────────────────────────────────────────────────────
    var pickupLat        by mutableStateOf(0.0)
    var pickupLng        by mutableStateOf(0.0)
    var pickupAddress    by mutableStateOf("")

    // ── Dropoff pin ───────────────────────────────────────────────────────────
    var dropoffLat       by mutableStateOf(0.0)
    var dropoffLng       by mutableStateOf(0.0)
    var dropoffAddress   by mutableStateOf("")

    // ── Errand note ───────────────────────────────────────────────────────────
    var note             by mutableStateOf("")

    // ── Picker mode ───────────────────────────────────────────────────────────
    var pickerMode       by mutableStateOf(PickerMode.None)

    // ── Bottom sheet expansion ────────────────────────────────────────────────
    /** 0f = collapsed, 1f = fully expanded (75% screen) */
    var sheetExpansion   by mutableStateOf(0f)

    // ── Booking ───────────────────────────────────────────────────────────────
    var bookingLoading   by mutableStateOf(false)
    var bookingError     by mutableStateOf<String?>(null)

    // ── Sheet step ────────────────────────────────────────────────────────────
    enum class SheetStep { Main, Summary }
    var sheetStep        by mutableStateOf(SheetStep.Main)

    private var activeTripChecked = false
    private var pollingJob: Job?  = null

    init {
        viewModelScope.launch { riderName = session.fullName.first() ?: "" }
        loadVehicleTypes()
    }

    // ── Active trip recovery ──────────────────────────────────────────────────
    fun checkActiveTrip(onFound: (tripId: String, status: String) -> Unit) {
        if (activeTripChecked) return
        activeTripChecked = true
        viewModelScope.launch {
            runCatching {
                val resp = api.getActiveTrip()
                if (resp.isSuccessful) {
                    val trip = resp.body()?.data ?: return@runCatching
                    onFound(trip.id, trip.status)
                }
            }
        }
    }

    // ── Vehicle types ─────────────────────────────────────────────────────────
    private fun loadVehicleTypes() {
        viewModelScope.launch {
            runCatching {
                val resp = api.getVehicleTypes()
                if (resp.isSuccessful) {
                    vehicleTypes = resp.body()?.data ?: emptyList()
                    if (selectedType == null) selectedType = vehicleTypes.firstOrNull()
                }
            }
        }
    }

    // ── GPS ───────────────────────────────────────────────────────────────────
    fun onLocationAvailable(lat: Double, lng: Double) {
        if (!hasInitialCenter) {
            hasInitialCenter = true
            // Seed pickup with current location on first fix only
            if (pickupLat == 0.0 && pickupLng == 0.0) {
                pickupLat = lat
                pickupLng = lng
                reverseGeocode(lat, lng) { addr -> pickupAddress = addr }
            }
        }
        userLocation = Pair(lat, lng)
        if (pollingJob?.isActive != true) startPolling()
    }

    // ── Polling ───────────────────────────────────────────────────────────────
    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                fetchNearbyDrivers()
                delay(5_000L)
            }
        }
    }

    private suspend fun fetchNearbyDrivers() {
        val loc = userLocation ?: return
        runCatching {
            val resp = api.getNearbyDrivers(loc.first, loc.second)
            if (resp.isSuccessful) {
                val drivers = resp.body()?.data ?: emptyList()
                nearbyDrivers = drivers
                countByType = drivers.groupingBy { it.vehicleType }.eachCount()
            }
        }
    }

    // ── Pin placement ─────────────────────────────────────────────────────────
    fun onMapTapped(lat: Double, lng: Double) {
        when (pickerMode) {
            PickerMode.Pickup -> {
                pickupLat = lat
                pickupLng = lng
                reverseGeocode(lat, lng) { addr -> pickupAddress = addr }
            }
            PickerMode.Dropoff -> {
                dropoffLat = lat
                dropoffLng = lng
                reverseGeocode(lat, lng) { addr -> dropoffAddress = addr }
            }
            PickerMode.None -> Unit
        }
    }

    fun cancelPickerMode() { pickerMode = PickerMode.None }

    // ── Geocoding ─────────────────────────────────────────────────────────────

    private fun reverseGeocode(lat: Double, lng: Double, onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = java.net.URL(
                        "https://nominatim.openstreetmap.org/reverse" +
                                "?lat=$lat&lon=$lng&format=json&addressdetails=0"
                    ).openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("User-Agent", "AntarRiderApp/1.0")
                    conn.connectTimeout = 5_000
                    conn.readTimeout    = 5_000
                    val json = org.json.JSONObject(conn.inputStream.bufferedReader().readText())
                    json.optString("display_name")
                        .split(",")
                        .take(3)
                        .joinToString(", ")
                        .ifEmpty { null }
                }.getOrNull()
            }
            onResult(result ?: "%.5f, %.5f".format(lat, lng))
        }
    }

    private suspend fun geocode(address: String): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val encoded = java.net.URLEncoder.encode(address, "UTF-8")
                val conn = java.net.URL(
                    "https://nominatim.openstreetmap.org/search" +
                            "?q=$encoded&format=json&limit=1&countrycodes=id"
                ).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "AntarRiderApp/1.0")
                conn.connectTimeout = 6_000
                conn.readTimeout    = 6_000
                val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                if (arr.length() == 0) return@runCatching null
                val obj = arr.getJSONObject(0)
                Pair(obj.getDouble("lat"), obj.getDouble("lon"))
            }.getOrNull()
        }

    fun geocodeDropoff(address: String, onResult: (Double, Double) -> Unit) {
        viewModelScope.launch {
            val coords = geocode(address)
            if (coords != null) {
                dropoffLat = coords.first
                dropoffLng = coords.second
                onResult(coords.first, coords.second)
            }
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────
    fun validate(): String? = when {
        selectedType == null                              -> "Pilih jenis kendaraan"
        pickupLat == 0.0 && pickupLng == 0.0             -> "Tentukan lokasi penjemputan"
        tripType == "transport" &&
                dropoffLat == 0.0 && dropoffLng == 0.0   -> "Tentukan lokasi tujuan"
        tripType == "errand" && note.isBlank()            -> "Isi keterangan keperluan"
        else                                              -> null
    }

    // ── Booking ───────────────────────────────────────────────────────────────
    // REQ-DUP fix: bookingLoading is set to true BEFORE the coroutine is
    // launched so a second tap between launch and first suspension cannot
    // sneak through and fire a duplicate request.
    fun requestRide(onSuccess: (tripId: String) -> Unit, onError: (String) -> Unit) {
        if (bookingLoading) return          // guard against concurrent calls
        val type = selectedType ?: return
        bookingLoading = true               // set BEFORE launch, not inside it
        bookingError   = null
        viewModelScope.launch {
            runCatching {

                // ── Geocode pickup if map tap hasn't set coords yet ────────────
                if (pickupLat == 0.0 || pickupLng == 0.0) {
                    if (pickupAddress.isBlank()) {
                        onError("Masukkan alamat atau pilih lokasi penjemputan di peta")
                        return@runCatching
                    }
                    val coords = geocode(pickupAddress)
                    if (coords == null) {
                        onError("Tidak dapat menemukan koordinat untuk alamat penjemputan. Coba pilih lokasi di peta.")
                        return@runCatching
                    }
                    pickupLat = coords.first
                    pickupLng = coords.second
                }

                // ── Geocode dropoff if coords are missing (transport only) ────
                if (tripType == "transport" && (dropoffLat == 0.0 || dropoffLng == 0.0)) {
                    val coords = geocode(dropoffAddress)
                    dropoffLat = coords?.first  ?: pickupLat
                    dropoffLng = coords?.second ?: pickupLng
                }

                val body = RequestRideRequest(
                    tripType       = tripType,
                    vehicleTypeId  = type.id,
                    pickupLat      = pickupLat,
                    pickupLng      = pickupLng,
                    pickupAddress  = pickupAddress.ifBlank {
                        "%.5f, %.5f".format(pickupLat, pickupLng)
                    },
                    dropoffLat     = if (tripType == "transport") dropoffLat  else null,
                    dropoffLng     = if (tripType == "transport") dropoffLng  else null,
                    dropoffAddress = if (tripType == "transport") dropoffAddress.ifBlank {
                        "%.5f, %.5f".format(dropoffLat, dropoffLng)
                    } else null,
                    note           = if (tripType == "errand") note.trim() else null,
                )
                val resp = api.requestRide(body)
                if (resp.isSuccessful) {
                    val tripId = resp.body()?.data?.tripId
                    if (tripId != null) { resetBooking(); onSuccess(tripId) }
                    else onError("Gagal membuat pesanan")
                } else {
                    val err = runCatching {
                        org.json.JSONObject(resp.errorBody()?.string() ?: "")
                            .optString("error").takeIf { it.isNotEmpty() }
                    }.getOrNull() ?: "Gagal membuat pesanan"
                    onError(err)
                }
            }.onFailure { onError("Tidak dapat terhubung ke server") }
            bookingLoading = false
        }
    }

    private fun resetBooking() {
        tripType       = "transport"
        selectedType   = vehicleTypes.firstOrNull()
        dropoffLat     = 0.0
        dropoffLng     = 0.0
        dropoffAddress = ""
        note           = ""
        bookingError   = null
        sheetStep      = SheetStep.Main
        sheetExpansion = 0f
    }

    override fun onCleared() { super.onCleared(); pollingJob?.cancel() }
}