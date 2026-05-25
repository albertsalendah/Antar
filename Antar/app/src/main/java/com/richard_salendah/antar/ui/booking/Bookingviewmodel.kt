package com.richard_salendah.antar.ui.booking

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.richard_salendah.antar.Antar
import com.richard_salendah.antar.data.model.RequestRideRequest
import com.richard_salendah.antar.data.model.VehicleTypeResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class BookingViewModel(app: Application) : AndroidViewModel(app) {

    private val api = (app as Antar).apiService

    // ── Trip configuration ────────────────────────────────────────────────────
    var tripType     by mutableStateOf("transport") // "transport" | "errand"
    var selectedType by mutableStateOf<VehicleTypeResponse?>(null)

    // ── Pickup ────────────────────────────────────────────────────────────────
    var pickupAddress by mutableStateOf("")
    var pickupLat     by mutableStateOf(0.0)
    var pickupLng     by mutableStateOf(0.0)

    // ── Dropoff (transport only) ──────────────────────────────────────────────
    var dropoffAddress by mutableStateOf("")
    var dropoffLat     by mutableStateOf(0.0)
    var dropoffLng     by mutableStateOf(0.0)

    // ── Errand note ───────────────────────────────────────────────────────────
    var note by mutableStateOf("")

    // ── Vehicle types list ────────────────────────────────────────────────────
    var vehicleTypes by mutableStateOf<List<VehicleTypeResponse>>(emptyList())
    var typesLoading by mutableStateOf(false)
    var typesError   by mutableStateOf<String?>(null)

    // ── Booking submission ────────────────────────────────────────────────────
    var bookingLoading by mutableStateOf(false)
    var bookingError   by mutableStateOf<String?>(null)

    init { loadVehicleTypes() }

    // ── Seed pickup coords from GPS once on screen entry ──────────────────────
    // No-op if coords were already set to avoid overwriting after the user moved.
    fun seedLocation(lat: Double, lng: Double) {
        if (pickupLat == 0.0 && pickupLng == 0.0) {
            pickupLat = lat
            pickupLng = lng
        }
    }

    fun loadVehicleTypes() {
        viewModelScope.launch {
            typesLoading = true
            typesError   = null
            runCatching {
                val resp = api.getVehicleTypes()
                if (resp.isSuccessful) {
                    vehicleTypes = resp.body()?.data ?: emptyList()
                } else {
                    typesError = "Gagal memuat jenis kendaraan"
                }
            }.onFailure { typesError = "Tidak dapat terhubung ke server" }
            typesLoading = false
        }
    }

    // Returns an error string if the picker form is incomplete, null if OK.
    fun validatePicker(): String? = when {
        selectedType == null     -> "Pilih jenis kendaraan terlebih dahulu"
        pickupAddress.isBlank()  -> "Isi alamat penjemputan"
        pickupLat == 0.0 && pickupLng == 0.0 ->
            "Lokasi GPS belum terdeteksi, coba lagi sebentar"
        tripType == "transport" && dropoffAddress.isBlank() -> "Isi alamat tujuan"
        tripType == "errand"    && note.isBlank()           -> "Isi keterangan keperluan"
        else                    -> null
    }

    fun requestRide(onSuccess: (tripId: String) -> Unit, onError: (String) -> Unit) {
        val type = selectedType ?: return
        viewModelScope.launch {
            bookingLoading = true
            bookingError   = null
            try {
                // Geocode dropoff for transport trips when coordinates are missing.
                // Falls back to pickup coords if Nominatim returns nothing —
                // drivers on small islands navigate by address text anyway.
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
                    pickupAddress  = pickupAddress.trim(),
                    dropoffLat     = if (tripType == "transport") dropoffLat  else null,
                    dropoffLng     = if (tripType == "transport") dropoffLng  else null,
                    dropoffAddress = if (tripType == "transport") dropoffAddress.trim() else null,
                    note           = if (tripType == "errand")   note.trim() else null,
                )

                val resp = api.requestRide(body)
                if (resp.isSuccessful) {
                    val tripId = resp.body()?.data?.tripId
                    if (tripId != null) {
                        resetBooking()
                        onSuccess(tripId)
                    } else {
                        onError("Gagal membuat pesanan, coba lagi")
                    }
                } else {
                    onError(parseError(resp.errorBody()?.string()) ?: "Gagal membuat pesanan")
                }
            } catch (_: Exception) {
                onError("Tidak dapat terhubung ke server")
            } finally {
                bookingLoading = false
            }
        }
    }

    private fun resetBooking() {
        tripType       = "transport"
        selectedType   = null
        pickupAddress  = ""
        pickupLat      = 0.0
        pickupLng      = 0.0
        dropoffAddress = ""
        dropoffLat     = 0.0
        dropoffLng     = 0.0
        note           = ""
        bookingError   = null
    }

    // ── Nominatim geocoding (OSM, no API key required) ────────────────────────
    private suspend fun geocode(address: String): Pair<Double, Double>? =
        withContext(Dispatchers.IO) {
            try {
                val encoded = java.net.URLEncoder.encode(address, "UTF-8")
                val conn    = java.net.URL(
                    "https://nominatim.openstreetmap.org/search" +
                            "?q=$encoded&format=json&limit=1&countrycodes=id"
                ).openConnection() as java.net.HttpURLConnection
                conn.setRequestProperty("User-Agent", "AntarRiderApp/1.0")
                conn.connectTimeout = 6_000
                conn.readTimeout    = 6_000
                val arr = JSONArray(conn.inputStream.bufferedReader().readText())
                if (arr.length() == 0) return@withContext null
                val obj = arr.getJSONObject(0)
                Pair(obj.getDouble("lat"), obj.getDouble("lon"))
            } catch (_: Exception) { null }
        }

    private fun parseError(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            org.json.JSONObject(body).optString("error").takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }
}