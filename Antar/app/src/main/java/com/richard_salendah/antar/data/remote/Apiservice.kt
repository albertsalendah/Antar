package com.richard_salendah.antar.data.remote

import com.richard_salendah.antar.data.model.*
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    // ── Auth ──────────────────────────────────────────────────────────────────
    @POST("api/v1/rider/register")
    suspend fun register(@Body body: RegisterRequest): Response<ApiResponse<RegisterResponse>>

    @POST("api/v1/rider/login")
    suspend fun login(@Body body: LoginRequest): Response<ApiResponse<AuthData>>

    @POST("api/v1/rider/refresh")
    suspend fun refreshToken(@Body body: RefreshRequest): Response<ApiResponse<AuthData>>

    // ── Device ────────────────────────────────────────────────────────────────
    @POST("api/v1/rider/fcm-token")
    suspend fun saveFcmToken(@Body body: FcmTokenRequest): Response<ApiResponse<MessageResponse>>

    // ── Profile ───────────────────────────────────────────────────────────────
    @GET("api/v1/rider/profile")
    suspend fun getProfile(): Response<ApiResponse<ProfileResponse>>

    @PATCH("api/v1/rider/profile")
    suspend fun updateProfile(@Body body: UpdateProfileRequest): Response<ApiResponse<MessageResponse>>

    @Multipart
    @POST("api/v1/rider/avatar")
    suspend fun uploadAvatar(@Part avatar: MultipartBody.Part): Response<ApiResponse<AvatarResponse>>

    // ── Vehicle types (used for picker screen) ────────────────────────────────
    @GET("api/v1/driver/vehicle-types")
    suspend fun getVehicleTypes(): Response<ApiResponse<List<VehicleTypeResponse>>>

    // ── Nearby drivers ────────────────────────────────────────────────────────
    @GET("api/v1/rider/nearby-drivers")
    suspend fun getNearbyDrivers(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("vehicle_type_id") vehicleTypeId: Int? = null,
    ): Response<ApiResponse<List<NearbyDriverResponse>>>

    // ── Trips ─────────────────────────────────────────────────────────────────
    @POST("api/v1/rider/request-ride")
    suspend fun requestRide(@Body body: RequestRideRequest): Response<ApiResponse<RequestRideResponse>>

    @GET("api/v1/rider/trips/active")
    suspend fun getActiveTrip(): Response<ApiResponse<TripResponse?>>

    @GET("api/v1/rider/trips")
    suspend fun listTrips(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
    ): Response<ApiResponse<List<TripResponse>>>

    @GET("api/v1/rider/trips/{tripId}")
    suspend fun getTrip(@Path("tripId") tripId: String): Response<ApiResponse<TripResponse>>

    @POST("api/v1/rider/trips/{tripId}/accept")
    suspend fun acceptOffer(@Path("tripId") tripId: String): Response<ApiResponse<MessageResponse>>

    @POST("api/v1/rider/trips/{tripId}/reject")
    suspend fun rejectOffer(@Path("tripId") tripId: String): Response<ApiResponse<MessageResponse>>

    @POST("api/v1/rider/trips/{tripId}/counter")
    suspend fun counterOffer(
        @Path("tripId") tripId: String,
        @Body body: CounterOfferRequest,
    ): Response<ApiResponse<CounterOfferResponse>>

    @POST("api/v1/rider/trips/{tripId}/cancel")
    suspend fun cancelTrip(@Path("tripId") tripId: String): Response<ApiResponse<MessageResponse>>

    @POST("api/v1/rider/trips/{tripId}/rate")
    suspend fun rateDriver(
        @Path("tripId") tripId: String,
        @Body body: RateRequest,
    ): Response<ApiResponse<MessageResponse>>

    // ── Candidate review ──────────────────────────────────────────────────────

    // Approve the suggested candidate driver; server fires FCM to notify them.
    @POST("api/v1/rider/trips/{tripId}/approve-candidate")
    suspend fun approveCandidate(
        @Path("tripId") tripId: String,
        @Body body: ApproveCandidateRequest,
    ): Response<ApiResponse<MessageResponse>>

    // Reject the current candidate; server finds the next nearest eligible driver.
    // Response data contains the new candidate_driver_id (null = no drivers left).
    @POST("api/v1/rider/trips/{tripId}/reject-candidate")
    suspend fun rejectCandidate(
        @Path("tripId") tripId: String,
    ): Response<ApiResponse<RejectCandidateResponse>>

    // List all drivers previously rejected for this trip (exclusion list).
    // Used by NoDriverFoundScreen to let the rider pick from earlier rejections.
    @GET("api/v1/rider/trips/{tripId}/rejected-drivers")
    suspend fun getRejectedDrivers(
        @Path("tripId") tripId: String,
    ): Response<ApiResponse<List<RejectedDriverResponse>>>

    // Re-assign a previously rejected driver. Only callable when
    // candidate_driver_id IS NULL (no current candidate). Auto-approves.
    @POST("api/v1/rider/trips/{tripId}/reselect-driver")
    suspend fun reselectDriver(
        @Path("tripId") tripId: String,
        @Body body: ReselectDriverRequest,
    ): Response<ApiResponse<MessageResponse>>
}