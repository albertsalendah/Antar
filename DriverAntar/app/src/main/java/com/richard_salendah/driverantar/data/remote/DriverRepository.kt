package com.richard_salendah.driverantar.data.remote

import android.util.Log
import com.richard_salendah.driverantar.data.model.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response

class DriverRepository(private val api: DriverApiService) {

    // ── Auth ──────────────────────────────────────────────────────────────────

    suspend fun register(fullName: String, email: String, password: String, phone: String)
            : Result<RegisterResponse> = safeCall {
        api.register(RegisterRequest(fullName, email, password, phone)).unwrap()
    }

    suspend fun login(email: String, password: String): Result<LoginResponse> = safeCall {
        api.login(LoginRequest(email, password)).unwrap()
    }

    suspend fun refreshToken(refreshToken: String): Result<RefreshResponse> = safeCall {
        api.refreshToken(RefreshRequest(refreshToken)).unwrap()
    }

    // ── Device ────────────────────────────────────────────────────────────────

    suspend fun saveFcmToken(token: String, fcmToken: String): Result<Unit> = safeCall {
        api.saveFcmToken(token, FCMTokenRequest(fcmToken)).unwrapVoid()
    }

    // ── Profile ───────────────────────────────────────────────────────────────

    suspend fun getProfile(token: String): Result<ProfileResponse> = safeCall {
        api.getProfile(token).unwrap()
    }

    suspend fun updateProfile(token: String, fullName: String = "", email: String? = null)
            : Result<Unit> = safeCall {
        api.updateProfile(token, UpdateProfileRequest(fullName, email)).unwrapVoid()
    }

    suspend fun uploadAvatar(token: String, imageBytes: ByteArray, mimeType: String)
            : Result<String> = safeCall {
        val ext  = when (mimeType) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
        val body = imageBytes.toRequestBody(mimeType.toMediaTypeOrNull())
        val part = MultipartBody.Part.createFormData("avatar", "avatar.$ext", body)
        api.uploadAvatar(token, part).unwrap().avatar_url
    }

    // ── Vehicle types ─────────────────────────────────────────────────────────

    suspend fun getVehicleTypes(token: String): Result<List<VehicleType>> = safeCall {
        api.getVehicleTypes(token).unwrap()
    }

    // ── Vehicles ──────────────────────────────────────────────────────────────

    suspend fun listVehicles(token: String): Result<List<VehicleResponse>> = safeCall {
        api.listVehicles(token).unwrap()
    }

    suspend fun addVehicle(token: String, req: AddVehicleRequest): Result<Unit> = safeCall {
        api.addVehicle(token, req).unwrapVoid()
    }

    suspend fun deleteVehicle(token: String, vehicleId: String): Result<Unit> = safeCall {
        api.deleteVehicle(token, vehicleId).unwrapVoid()
    }

    suspend fun setActiveVehicle(token: String, vehicleId: String): Result<Unit> = safeCall {
        api.setActiveVehicle(token, vehicleId).unwrapVoid()
    }

    // ── Location ──────────────────────────────────────────────────────────────

    suspend fun updateLocation(token: String, lat: Double, lng: Double): Boolean = try {
        api.updateLocation(token, DriverLocationRequest(lat, lng)).isSuccessful
    } catch (e: Exception) {
        Log.e("DriverRepository", "updateLocation failed", e)
        false
    }

    suspend fun goOffline(token: String) {
        try { api.goOffline(token) }
        catch (e: Exception) { Log.e("DriverRepository", "goOffline failed", e) }
    }

    // ── Trips ─────────────────────────────────────────────────────────────────

    /** Returns null when no active trip exists (server returns null data). */
    suspend fun getActiveTrip(token: String): Result<DriverTripResponse?> = safeCall {
        api.getActiveTrip(token).unwrapNullable()
    }

    suspend fun getIncomingTrips(token: String): Result<List<IncomingTripResponse>> = safeCall {
        api.getIncomingTrips(token).unwrap()
    }

    suspend fun listTrips(token: String, limit: Int = 20, offset: Int = 0)
            : Result<List<DriverTripResponse>> = safeCall {
        api.listTrips(token, limit, offset).unwrap()
    }

    suspend fun offerPrice(token: String, tripId: String, fare: Double): Result<Unit> = safeCall {
        api.offerPrice(token, tripId, OfferPriceRequest(fare)).unwrapVoid()
    }

    suspend fun counterOffer(token: String, tripId: String, fare: Double): Result<Unit> = safeCall {
        api.counterOffer(token, tripId, CounterOfferRequest(fare)).unwrapVoid()
    }

    suspend fun startTrip(token: String, tripId: String): Result<Unit> = safeCall {
        api.startTrip(token, tripId).unwrapVoid()
    }

    suspend fun completeTrip(token: String, tripId: String): Result<Unit> = safeCall {
        api.completeTrip(token, tripId).unwrapVoid()
    }

    suspend fun cancelTrip(token: String, tripId: String): Result<Unit> = safeCall {
        api.cancelTrip(token, tripId).unwrapVoid()
    }

    suspend fun rateRider(token: String, tripId: String, score: Int, comment: String)
            : Result<Unit> = safeCall {
        api.rateRider(token, tripId, RateRiderRequest(score, comment)).unwrapVoid()
    }

    // ── Earnings ──────────────────────────────────────────────────────────────
    suspend fun getDailyEarnings(token: String): Result<List<DailyEarning>> = runCatching {
        val resp = api.getDailyEarnings(token)
        resp.body()?.data ?: emptyList()
    }

    suspend fun getEarnings(token: String): Result<EarningsSummary> = safeCall {
        api.getEarnings(token).unwrap()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun <T> safeCall(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Exception) {
        Log.e("DriverRepository", "API error: ${e.message}")
        Result.failure(e)
    }
}

private fun <T> Response<ApiResponse<T>>.unwrap(): T {
    if (!isSuccessful) error("HTTP ${code()}: ${errorBody()?.string() ?: message()}")
    return body()?.data ?: error("Response data is null")
}

private fun <T> Response<ApiResponse<T>>.unwrapNullable(): T? {
    if (!isSuccessful) error("HTTP ${code()}: ${errorBody()?.string() ?: message()}")
    return body()?.data
}

private fun Response<ApiResponse<Unit>>.unwrapVoid() {
    if (!isSuccessful) error("HTTP ${code()}: ${errorBody()?.string() ?: message()}")
}