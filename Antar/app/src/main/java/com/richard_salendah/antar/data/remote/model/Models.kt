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
    // Driver info — populated once a driver is assigned
    @SerializedName("driver_name")          val driverName: String = "",
    @SerializedName("driver_phone")         val driverPhone: String = "",
    // Option A: driver live location via polling.
    // Both are 0.0 when no driver is assigned or driver has no GPS fix.
    // When migrating to Option B (Realtime), these fields stay — only the
    // data source changes (Realtime push instead of poll response).
    @SerializedName("driver_lat")           val driverLat: Double = 0.0,
    @SerializedName("driver_lng")           val driverLng: Double = 0.0,
    // Rating state
    @SerializedName("rider_has_rated")      val riderHasRated: Boolean = false,
)
data class CounterOfferRequest(@SerializedName("offered_fare") val offeredFare: Double)
data class CounterOfferResponse(
    val message: String,
    @SerializedName("offered_fare") val offeredFare: Double,
)
data class RateRequest(val score: Int, val comment: String? = null)

// ── Shared ────────────────────────────────────────────────────────────────────
data class MessageResponse(val message: String)