package com.richard_salendah.driverantar.data.model

data class ApiResponse<T>(val status: String, val data: T?)

// ── Auth ──────────────────────────────────────────────────────────────────────

data class RegisterRequest(val full_name: String, val email: String,
                           val password: String, val phone_number: String)
data class LoginRequest(val email: String, val password: String)
data class RegisterResponse(val needs_confirmation: Boolean, val message: String,
                            val access_token: String?, val refresh_token: String?, val driver_id: String?)
data class LoginResponse(val access_token: String, val refresh_token: String,
                         val driver_id: String, val full_name: String)
data class RefreshRequest(val refresh_token: String)
data class RefreshResponse(val access_token: String, val refresh_token: String,
                           val driver_id: String, val full_name: String)

// ── Profile ───────────────────────────────────────────────────────────────────

data class ProfileResponse(
    val id: String,
    val full_name: String,
    val phone_number: String,
    val email: String,
    val avatar_url: String?,
    val is_online: Boolean,
    val active_vehicle_id: String?,
    val island_id: Int?,
    val island_name: String?,
    // Populated via Postgres trigger after each rating — null until first rating
    val avg_rating: Double?,
    val rating_count: Int
)

data class UpdateProfileRequest(val full_name: String = "", val email: String? = null)
data class AvatarUploadResponse(val avatar_url: String)

// ── FCM device token ──────────────────────────────────────────────────────────

/** Sent to POST /api/v1/driver/fcm-token after every login and onNewToken() */
data class FCMTokenRequest(val token: String)

// ── Vehicle types ─────────────────────────────────────────────────────────────

data class VehicleType(val id: Int, val name: String,
                       val code: String, val description: String)

// ── Vehicles ──────────────────────────────────────────────────────────────────

data class AddVehicleRequest(val vehicle_type_id: Int, val license_plate: String,
                             val make: String, val model: String, val year: Int, val color: String)
data class VehicleResponse(val id: String, val vehicle_type_id: Int,
                           val vehicle_type: String, val license_plate: String, val make: String,
                           val model: String, val year: Int, val color: String, val is_active: Boolean)

// ── Location ──────────────────────────────────────────────────────────────────

data class DriverLocationRequest(val lat: Double, val lng: Double)

// ── Trips — incoming ─────────────────────────────────────────────────────────

/**
 * Returned by GET /api/v1/driver/trips/incoming.
 * Each card shows trip type, addresses, note (for errands), distance, and
 * the default fare floor the driver must meet or exceed when offering.
 */
data class IncomingTripResponse(
    val id: String,
    val rider_id: String,
    val rider_name: String,
    val trip_type: String,           // "transport" | "errand"
    val note: String?,               // prominently shown for errands
    val pickup_address: String,
    val dropoff_address: String?,    // null for errands if rider didn't set one
    val distance_m: Double?,
    val default_fare: Double,        // floor — driver must offer ≥ this
    val vehicle_type: String,
    val payment_method: String,
    val created_at: String,
    val candidate_approved_at: String?,   // drives the TripCard countdown
    val rider_avatar_url: String?
)

// ── Trips — offer / counter ───────────────────────────────────────────────────

data class OfferPriceRequest(val offered_fare: Double)

data class CounterOfferRequest(val offered_fare: Double)

// ── Trips — full detail (active + history) ────────────────────────────────────

/**
 * Returned by GET /driver/trips/active and GET /driver/trips (history).
 *
 * Negotiation state fields:
 *   [last_offer_by]        — "driver" | "rider" | null (whose turn it is to respond)
 *   [offer_round]          — how many offer/counter rounds have happened
 *   [driver_counter_count] — how many times the driver has countered so far
 *   [rider_counter_count]  — how many times the rider has countered so far
 *
 * The UI uses these to decide what buttons to show:
 *   offered + last_offer_by=driver → waiting for rider (show offered fare, no action)
 *   offered + last_offer_by=rider  → rider countered (show Accept / Counter / Reject)
 */
data class DriverTripResponse(
    val id: String,
    val rider_id: String,
    val rider_name: String,
    val rider_phone: String,
    val status: String,              // requested|offered|agreed|in_progress|completed|cancelled
    val trip_type: String,
    val note: String?,
    val pickup_address: String,
    val dropoff_address: String?,
    val pickup_lat: Double,
    val pickup_lng: Double,
    val dropoff_lat: Double?,
    val dropoff_lng: Double?,
    val offered_fare: Double?,
    val fare: Double?,               // locked in after rider accepts
    val payment_method: String,
    // Negotiation
    val last_offer_by: String?,
    val offer_round: Int,
    val driver_counter_count: Int,
    val rider_counter_count: Int,
    val created_at: String,
    val updated_at: String,
    // null = driver hasn't rated this trip yet
    val rider_rating_given: Int?
)

// ── Ratings ───────────────────────────────────────────────────────────────────

data class RateRiderRequest(
    val score: Int,       // 1–5
    val comment: String   // empty string if no comment
)

// ── Earnings ──────────────────────────────────────────────────────────────────
data class DailyEarning(
    val date:  String,  // "2026-05-03"
    val total: Double,
    val trips: Int
)

data class EarningsSummary(
    val today_total: Double,
    val today_trips: Int,
    val week_total: Double,
    val week_trips: Int,
    val month_total: Double,
    val month_trips: Int,
    val all_time_total: Double,
    val all_time_trips: Int,
    val avg_rating: Double?,
    val rating_count: Int
)