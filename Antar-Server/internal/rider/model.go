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

	// Driver info — populated once a driver is assigned (status >= offered)
	DriverName  string `json:"driver_name"`
	DriverPhone string `json:"driver_phone"`

	// Driver live location — Option A (polling).
	// Both are 0 when no driver is assigned yet or driver has no location fix.
	// When migrating to Option B (Realtime), these fields stay in the model
	// but are populated by the Realtime handler instead of the DB join.
	DriverLat float64 `json:"driver_lat"`
	DriverLng float64 `json:"driver_lng"`

	// Rating state — true if the rider has already rated this trip
	RiderHasRated bool `json:"rider_has_rated"`
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
