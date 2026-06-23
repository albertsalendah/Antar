package com.richard_salendah.antar.data.model

import com.google.gson.annotations.SerializedName

// ── Generic wrapper ───────────────────────────────────────────────────────────
data class ApiResponse<T>(val data: T? = null, val error: String? = null)

// ── Auth ──────────────────────────────────────────────────────────────────────
data class RegisterRequest(
    val email: String,
    val password: String,
    @SerializedName("full_name")    val fullName: String,
    @SerializedName("phone_number") val phoneNumber: String,
)
data class RegisterResponse(
    @SerializedName("needs_confirmation") val needsConfirmation: Boolean,
    val message: String? = null,
    @SerializedName("access_token")  val accessToken: String? = null,
    @SerializedName("refresh_token") val refreshToken: String? = null,
    @SerializedName("rider_id")      val riderId: String? = null,
)
data class LoginRequest(val email: String, val password: String)
data class RefreshRequest(@SerializedName("refresh_token") val refreshToken: String)
data class AuthData(
    @SerializedName("access_token")  val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("rider_id")      val riderId: String,
    @SerializedName("full_name")     val fullName: String,
)

// ── Device ────────────────────────────────────────────────────────────────────
data class FcmTokenRequest(val token: String)

// ── Profile ───────────────────────────────────────────────────────────────────
data class ProfileResponse(
    val id: String,
    @SerializedName("full_name")    val fullName: String,
    @SerializedName("phone_number") val phoneNumber: String,
    val email: String,
    @SerializedName("avatar_url")   val avatarUrl: String?,
    @SerializedName("island_id")    val islandId: Int?,
    @SerializedName("island_name")  val islandName: String?,
)
data class UpdateProfileRequest(
    @SerializedName("full_name") val fullName: String? = null,
    val email: String? = null,
)
data class AvatarResponse(@SerializedName("avatar_url") val avatarUrl: String)

// ── Vehicle types ─────────────────────────────────────────────────────────────
data class VehicleTypeResponse(
    val id: Int,
    val name: String,
    val code: String,
    val description: String,
)

// ── Nearby drivers ────────────────────────────────────────────────────────────
data class NearbyDriverResponse(
    @SerializedName("driver_id")    val driverId: String,
    @SerializedName("full_name")    val fullName: String,
    @SerializedName("vehicle_type") val vehicleType: String,
    val lat: Double,
    val lng: Double,
    @SerializedName("distance_m")   val distanceM: Double,
)

// ── Trips ─────────────────────────────────────────────────────────────────────
data class RequestRideRequest(
    @SerializedName("trip_type")       val tripType: String,
    @SerializedName("vehicle_type_id") val vehicleTypeId: Int,
    @SerializedName("pickup_lat")      val pickupLat: Double,
    @SerializedName("pickup_lng")      val pickupLng: Double,
    @SerializedName("pickup_address")  val pickupAddress: String,
    @SerializedName("dropoff_lat")     val dropoffLat: Double? = null,
    @SerializedName("dropoff_lng")     val dropoffLng: Double? = null,
    @SerializedName("dropoff_address") val dropoffAddress: String? = null,
    val note: String? = null,
    @SerializedName("payment_method")  val paymentMethod: String = "cash",
)
data class RequestRideResponse(
    @SerializedName("trip_id")   val tripId: String,
    val status: String,
    @SerializedName("trip_type") val tripType: String,
    val message: String,
)
data class TripResponse(
    val id: String,
    @SerializedName("rider_id")             val riderId: String,
    @SerializedName("driver_id")            val driverId: String?,
    @SerializedName("offered_by")           val offeredBy: String?,
    val status: String,
    @SerializedName("trip_type")            val tripType: String,
    val note: String?,
    @SerializedName("pickup_address")       val pickupAddress: String,
    @SerializedName("dropoff_address")      val dropoffAddress: String?,
    @SerializedName("offered_fare")         val offeredFare: Double?,
    val fare: Double?,
    @SerializedName("payment_method")       val paymentMethod: String,
    @SerializedName("island_id")            val islandId: Int?,
    // Negotiation state
    @SerializedName("last_offer_by")        val lastOfferBy: String?,
    @SerializedName("offer_round")          val offerRound: Int,
    @SerializedName("driver_counter_count") val driverCounterCount: Int,
    @SerializedName("rider_counter_count")  val riderCounterCount: Int,
    @SerializedName("created_at")           val createdAt: String,
    @SerializedName("updated_at")           val updatedAt: String,
    // Driver info
    @SerializedName("driver_name")          val driverName: String = "",
    @SerializedName("driver_phone")         val driverPhone: String = "",
    // Option A: driver live location via polling
    @SerializedName("driver_lat")           val driverLat: Double = 0.0,
    @SerializedName("driver_lng")           val driverLng: Double = 0.0,
    // Trip map coordinates — used to render pickup/dropoff pins and OSRM routes.
    // dropoffLat/dropoffLng are 0.0 for errand trips (no dropoff location).
    @SerializedName("pickup_lat")           val pickupLat: Double = 0.0,
    @SerializedName("pickup_lng")           val pickupLng: Double = 0.0,
    @SerializedName("dropoff_lat")          val dropoffLat: Double = 0.0,
    @SerializedName("dropoff_lng")          val dropoffLng: Double = 0.0,
    // Rating state
    @SerializedName("rider_has_rated")      val riderHasRated: Boolean = false,
    // Candidate-review state — populated by notify_nearest_driver_on_insert(),
    // process_trip_notification_timeouts(), and the candidate-review endpoints.
    // candidateDriverId: null = no candidate yet (or exhausted), or the UUID of the suggested driver.
    // candidateApproved: true once the rider approves via approve-candidate.
    // candidateApprovedAt: ISO UTC timestamp of approval; drives the 3-min countdown.
    @SerializedName("candidate_driver_id")        val candidateDriverId: String? = null,
    @SerializedName("candidate_approved")         val candidateApproved: Boolean = false,
    @SerializedName("candidate_approved_at")      val candidateApprovedAt: String? = null,
    @SerializedName("candidate_driver_name")      val candidateDriverName: String? = null,
    @SerializedName("candidate_driver_avatar_url") val candidateDriverAvatarUrl: String? = null,
    @SerializedName("candidate_vehicle_type")     val candidateVehicleType: String? = null,
    @SerializedName("candidate_driver_rating")    val candidateDriverRating: Double? = null,
    // notification_attempts > 0 means the cron has tried and failed at least once.
    // candidateDriverId == null && notificationAttempts > 0 = exhausted, no drivers left.
    @SerializedName("notification_attempts")      val notificationAttempts: Int = 0,
)
data class CounterOfferRequest(@SerializedName("offered_fare") val offeredFare: Double)
data class CounterOfferResponse(
    val message: String,
    @SerializedName("offered_fare") val offeredFare: Double,
)
data class RateRequest(val score: Int, val comment: String? = null)

// ── Candidate review ──────────────────────────────────────────────────────────

// Sent when the rider approves the suggested candidate driver.
// The server re-validates availability and fires FCM to the driver on success.
data class ApproveCandidateRequest(
    @SerializedName("driver_id") val driverId: String,
)

// Returned by POST reject-candidate.
// candidateDriverId is the newly assigned candidate's UUID, or null if no eligible drivers remain.
data class RejectCandidateResponse(
    @SerializedName("candidate_driver_id") val candidateDriverId: String? = null,
    val message: String = "",
)

// One entry in the trip's rejected-drivers exclusion list.
// isAvailable re-checks online + vehicle type + not on another trip at query time.
data class RejectedDriverResponse(
    @SerializedName("driver_id")    val driverId: String,
    @SerializedName("full_name")    val fullName: String,
    @SerializedName("avatar_url")   val avatarUrl: String? = null,
    @SerializedName("vehicle_type") val vehicleType: String,
    @SerializedName("avg_rating")   val avgRating: Double? = null,
    @SerializedName("rating_count") val ratingCount: Int = 0,
    @SerializedName("is_available") val isAvailable: Boolean = false,
)

// Sent when the rider picks a previously-rejected driver from the NoDriverFound screen.
// The server removes the exclusion, re-validates, and auto-approves.
data class ReselectDriverRequest(
    @SerializedName("driver_id") val driverId: String,
)

// ── Shared ────────────────────────────────────────────────────────────────────
data class MessageResponse(val message: String)