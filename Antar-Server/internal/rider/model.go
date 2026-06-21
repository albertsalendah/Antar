package rider

// ── Auth ──────────────────────────────────────────────────────────────────────

type RegisterRequest struct {
	Email       string `json:"email"        binding:"required,email"`
	Password    string `json:"password"     binding:"required,min=8"`
	FullName    string `json:"full_name"    binding:"required"`
	PhoneNumber string `json:"phone_number" binding:"required"`
}

type LoginRequest struct {
	Email    string `json:"email"    binding:"required,email"`
	Password string `json:"password" binding:"required"`
}

// ── Profile ───────────────────────────────────────────────────────────────────

type ProfileResponse struct {
	ID          string  `json:"id"`
	FullName    string  `json:"full_name"`
	PhoneNumber string  `json:"phone_number"`
	Email       string  `json:"email"`
	AvatarURL   *string `json:"avatar_url"`
	IslandID    *int    `json:"island_id"`
	IslandName  *string `json:"island_name"`
}

type UpdateProfileRequest struct {
	FullName string  `json:"full_name"`
	Email    *string `json:"email"`
}

type AvatarResponse struct {
	AvatarURL string `json:"avatar_url"`
}

// ── Devices ───────────────────────────────────────────────────────────────────

type FCMTokenRequest struct {
	Token string `json:"token" binding:"required"`
}

// ── Trips ─────────────────────────────────────────────────────────────────────

type RequestRideRequest struct {
	TripType       string  `json:"trip_type"       binding:"required,oneof=transport errand"`
	VehicleTypeID  int     `json:"vehicle_type_id" binding:"required,gt=0"`
	PickupLat      float64 `json:"pickup_lat"      binding:"required"`
	PickupLng      float64 `json:"pickup_lng"      binding:"required"`
	PickupAddress  string  `json:"pickup_address"  binding:"required"`
	DropoffLat     float64 `json:"dropoff_lat"`
	DropoffLng     float64 `json:"dropoff_lng"`
	DropoffAddress string  `json:"dropoff_address"`
	Note           string  `json:"note"`
	PaymentMethod  string  `json:"payment_method"`
}

type TripResponse struct {
	ID             string   `json:"id"`
	RiderID        string   `json:"rider_id"`
	DriverID       *string  `json:"driver_id"`
	OfferedBy      *string  `json:"offered_by"`
	Status         string   `json:"status"`
	TripType       string   `json:"trip_type"`
	Note           *string  `json:"note"`
	PickupAddress  string   `json:"pickup_address"`
	DropoffAddress *string  `json:"dropoff_address"`
	OfferedFare    *float64 `json:"offered_fare"`
	Fare           *float64 `json:"fare"`
	PaymentMethod  string   `json:"payment_method"`
	IslandID       *int     `json:"island_id"`

	// Negotiation state
	LastOfferBy        *string `json:"last_offer_by"`
	OfferRound         int     `json:"offer_round"`
	DriverCounterCount int     `json:"driver_counter_count"`
	RiderCounterCount  int     `json:"rider_counter_count"`

	CreatedAt string `json:"created_at"`
	UpdatedAt string `json:"updated_at"`

	// Driver info — populated once a driver is assigned
	DriverName  string `json:"driver_name"`
	DriverPhone string `json:"driver_phone"`

	// Driver live location — Option A (polling via DB join).
	// Option B (Realtime): same fields, different data source.
	// Both are 0.0 when no driver assigned or driver has no GPS fix.
	DriverLat float64 `json:"driver_lat"`
	DriverLng float64 `json:"driver_lng"`

	// Trip coordinates — used by the map to render pickup/dropoff pins
	// and to compute OSRM routes.
	// DropoffLat/DropoffLng are 0.0 for errand trips (no dropoff).
	PickupLat  float64 `json:"pickup_lat"`
	PickupLng  float64 `json:"pickup_lng"`
	DropoffLat float64 `json:"dropoff_lat"`
	DropoffLng float64 `json:"dropoff_lng"`

	// Rating state
	RiderHasRated bool `json:"rider_has_rated"`

	// Candidate-review state — populated by notify_nearest_driver_on_insert(),
	// process_trip_notification_timeouts(), and the candidate-review endpoints.
	CandidateDriverID        *string  `json:"candidate_driver_id"`
	CandidateApproved        bool     `json:"candidate_approved"`
	CandidateApprovedAt      *string  `json:"candidate_approved_at"`
	CandidateDriverName      *string  `json:"candidate_driver_name"`
	CandidateDriverAvatarURL *string  `json:"candidate_driver_avatar_url"`
	CandidateVehicleType     *string  `json:"candidate_vehicle_type"`
	CandidateDriverRating    *float64 `json:"candidate_driver_rating"`
	NotificationAttempts     int      `json:"notification_attempts"`
}

type CounterOfferRequest struct {
	OfferedFare float64 `json:"offered_fare" binding:"required,gt=0"`
}

type CounterOfferResponse struct {
	Message     string  `json:"message"`
	OfferedFare float64 `json:"offered_fare"`
}

type RateRequest struct {
	Score   int    `json:"score"   binding:"required,min=1,max=5"`
	Comment string `json:"comment"`
}

// ── Nearby drivers ────────────────────────────────────────────────────────────

type NearbyDriver struct {
	DriverID    string  `json:"driver_id"`
	FullName    string  `json:"full_name"`
	VehicleType string  `json:"vehicle_type"`
	Latitude    float64 `json:"lat"`
	Longitude   float64 `json:"lng"`
	DistanceM   float64 `json:"distance_m"`
}

// ── Shared ────────────────────────────────────────────────────────────────────

type MessageResponse struct {
	Message string `json:"message"`
}

// ── Candidate review ──────────────────────────────────────────────────────────

type ApproveCandidateRequest struct {
	DriverID string `json:"driver_id" binding:"required"`
}

type ReselectDriverRequest struct {
	DriverID string `json:"driver_id" binding:"required"`
}

type RejectedDriverResponse struct {
	DriverID    string   `json:"driver_id"`
	FullName    string   `json:"full_name"`
	AvatarURL   *string  `json:"avatar_url"`
	VehicleType string   `json:"vehicle_type"`
	AvgRating   *float64 `json:"avg_rating"`
	RatingCount int      `json:"rating_count"`
	IsAvailable bool     `json:"is_available"`
}
