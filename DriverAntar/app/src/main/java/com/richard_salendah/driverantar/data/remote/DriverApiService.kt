package com.richard_salendah.driverantar.data.remote

import com.richard_salendah.driverantar.data.model.*
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

interface DriverApiService {

    // ── Auth (public) ─────────────────────────────────────────────────────────

    @POST("api/v1/driver/register")
    suspend fun register(@Body request: RegisterRequest): Response<ApiResponse<RegisterResponse>>

    @POST("api/v1/driver/login")
    suspend fun login(@Body request: LoginRequest): Response<ApiResponse<LoginResponse>>

    @POST("api/v1/driver/refresh")
    suspend fun refreshToken(@Body request: RefreshRequest): Response<ApiResponse<RefreshResponse>>

    // ── Device (protected) ────────────────────────────────────────────────────

    /** Must be called after every login and whenever onNewToken() fires */
    @POST("api/v1/driver/fcm-token")
    suspend fun saveFcmToken(
        @Header("Authorization") token: String,
        @Body request: FCMTokenRequest
    ): Response<ApiResponse<Unit>>

    // ── Profile (protected) ───────────────────────────────────────────────────

    @GET("api/v1/driver/profile")
    suspend fun getProfile(@Header("Authorization") token: String): Response<ApiResponse<ProfileResponse>>

    @PATCH("api/v1/driver/profile")
    suspend fun updateProfile(
        @Header("Authorization") token: String,
        @Body request: UpdateProfileRequest
    ): Response<ApiResponse<Unit>>

    @Multipart
    @POST("api/v1/driver/avatar")
    suspend fun uploadAvatar(
        @Header("Authorization") token: String,
        @Part avatar: MultipartBody.Part
    ): Response<ApiResponse<AvatarUploadResponse>>

    // ── Vehicle types (protected) ─────────────────────────────────────────────

    @GET("api/v1/driver/vehicle-types")
    suspend fun getVehicleTypes(@Header("Authorization") token: String): Response<ApiResponse<List<VehicleType>>>

    // ── Vehicles (protected) ──────────────────────────────────────────────────

    @GET("api/v1/driver/vehicles")
    suspend fun listVehicles(@Header("Authorization") token: String): Response<ApiResponse<List<VehicleResponse>>>

    @POST("api/v1/driver/vehicles")
    suspend fun addVehicle(
        @Header("Authorization") token: String,
        @Body request: AddVehicleRequest
    ): Response<ApiResponse<Unit>>

    @DELETE("api/v1/driver/vehicles/{vehicle_id}")
    suspend fun deleteVehicle(
        @Header("Authorization") token: String,
        @Path("vehicle_id") vehicleId: String
    ): Response<ApiResponse<Unit>>

    @POST("api/v1/driver/vehicles/{vehicle_id}/set-active")
    suspend fun setActiveVehicle(
        @Header("Authorization") token: String,
        @Path("vehicle_id") vehicleId: String
    ): Response<ApiResponse<Unit>>

    // ── Location (protected) ──────────────────────────────────────────────────

    @POST("api/v1/driver/location")
    suspend fun updateLocation(
        @Header("Authorization") token: String,
        @Body request: DriverLocationRequest
    ): Response<ApiResponse<Unit>>

    @POST("api/v1/driver/offline")
    suspend fun goOffline(@Header("Authorization") token: String): Response<ApiResponse<Unit>>

    // ── Trips (protected) — static routes first ───────────────────────────────

    /**
     * Recovery endpoint — called on every app start and resume.
     * Returns the driver's current active trip (offered/agreed/in_progress),
     * or null if no active trip exists.
     */
    @GET("api/v1/driver/trips/active")
    suspend fun getActiveTrip(@Header("Authorization") token: String): Response<ApiResponse<DriverTripResponse?>>

    /**
     * Incoming trips on the driver's island matching their active vehicle type.
     * Driver polls this to see open requests.
     */
    @GET("api/v1/driver/trips/incoming")
    suspend fun getIncomingTrips(@Header("Authorization") token: String): Response<ApiResponse<List<IncomingTripResponse>>>

    /** Full trip history — completed and cancelled. Supports ?limit=&offset= */
    @GET("api/v1/driver/trips")
    suspend fun listTrips(
        @Header("Authorization") token: String,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Response<ApiResponse<List<DriverTripResponse>>>

    // ── Trips (protected) — per-trip actions ──────────────────────────────────

    /** Driver proposes a price. Atomic lock — first driver to offer wins. */
    @POST("api/v1/driver/trips/{trip_id}/offer")
    suspend fun offerPrice(
        @Header("Authorization") token: String,
        @Path("trip_id") tripId: String,
        @Body request: OfferPriceRequest
    ): Response<ApiResponse<Unit>>

    /** Driver counters after rider has submitted a counter-offer. */
    @POST("api/v1/driver/trips/{trip_id}/counter")
    suspend fun counterOffer(
        @Header("Authorization") token: String,
        @Path("trip_id") tripId: String,
        @Body request: CounterOfferRequest
    ): Response<ApiResponse<Unit>>

    /** Driver withdraws their pending offer — trip resets to requested and may auto-reassign to the next nearest candidate. */
    @POST("api/v1/driver/trips/{trip_id}/withdraw-offer")
    suspend fun withdrawOffer(
        @Header("Authorization") token: String,
        @Path("trip_id") tripId: String
    ): Response<ApiResponse<Unit>>

    /** Moves trip from agreed → in_progress. Driver taps when arriving at pickup. */
    @POST("api/v1/driver/trips/{trip_id}/start")
    suspend fun startTrip(
        @Header("Authorization") token: String,
        @Path("trip_id") tripId: String
    ): Response<ApiResponse<Unit>>

    @POST("api/v1/driver/trips/{trip_id}/arrive")
    suspend fun arriveAtPickup(
        @Header("Authorization") token: String,
        @Path("trip_id") tripId: String
    ): Response<ApiResponse<Unit>>

    /** Moves trip from in_progress → completed. */
    @POST("api/v1/driver/trips/{trip_id}/complete")
    suspend fun completeTrip(
        @Header("Authorization") token: String,
        @Path("trip_id") tripId: String
    ): Response<ApiResponse<Unit>>

    /** Driver cancels — only allowed when status = agreed (before start). */
    @POST("api/v1/driver/trips/{trip_id}/cancel")
    suspend fun cancelTrip(
        @Header("Authorization") token: String,
        @Path("trip_id") tripId: String
    ): Response<ApiResponse<Unit>>

    /** Rate the rider after trip completion. One submission per trip. */
    @POST("api/v1/driver/trips/{trip_id}/rate")
    suspend fun rateRider(
        @Header("Authorization") token: String,
        @Path("trip_id") tripId: String,
        @Body request: RateRiderRequest
    ): Response<ApiResponse<Unit>>

    // ── Earnings (protected) ──────────────────────────────────────────────────
    @GET("api/v1/driver/earnings/daily")
    suspend fun getDailyEarnings(
        @Header("Authorization") token: String
    ): Response<ApiResponse<List<DailyEarning>>>

    @GET("api/v1/driver/earnings")
    suspend fun getEarnings(@Header("Authorization") token: String): Response<ApiResponse<EarningsSummary>>
}