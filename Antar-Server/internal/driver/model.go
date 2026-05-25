package driver

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
	ID              string   `json:"id"`
	FullName        string   `json:"full_name"`
	PhoneNumber     string   `json:"phone_number"`
	Email           string   `json:"email"`
	AvatarURL       *string  `json:"avatar_url"`
	IsOnline        bool     `json:"is_online"`
	ActiveVehicleID *string  `json:"active_vehicle_id"`
	AvgRating       *float64 `json:"avg_rating"`
	RatingCount     int      `json:"rating_count"`
	IslandID        *int     `json:"island_id"`   // ← new
	IslandName      *string  `json:"island_name"` // ← new
}

type UpdateProfileRequest struct {
	FullName string  `json:"full_name"`
	Email    *string `json:"email"`
}

// ── Devices ───────────────────────────────────────────────────────────────────

type FCMTokenRequest struct {
	Token string `json:"token" binding:"required"`
}

// ── Vehicles ──────────────────────────────────────────────────────────────────

type VehicleType struct {
	ID          int    `json:"id"`
	Name        string `json:"name"`
	Code        string `json:"code"`
	Description string `json:"description"`
}

type AddVehicleRequest struct {
	VehicleTypeID int    `json:"vehicle_type_id" binding:"required"`
	LicensePlate  string `json:"license_plate"   binding:"required"`
	Make          string `json:"make"`
	Model         string `json:"model"`
	Year          int    `json:"year"`
	Color         string `json:"color"`
}

type UpdateVehicleRequest struct {
	Make     string `json:"make"`
	Model    string `json:"model"`
	Year     int    `json:"year"`
	Color    string `json:"color"`
	IsActive *bool  `json:"is_active"`
}

type VehicleResponse struct {
	ID            string `json:"id"`
	VehicleTypeID int    `json:"vehicle_type_id"`
	VehicleType   string `json:"vehicle_type"`
	LicensePlate  string `json:"license_plate"`
	Make          string `json:"make"`
	Model         string `json:"model"`
	Year          int    `json:"year"`
	Color         string `json:"color"`
	IsActive      bool   `json:"is_active"`
}

// ── Location ──────────────────────────────────────────────────────────────────

type UpdateLocationRequest struct {
	Latitude  float64 `json:"lat" binding:"required"`
	Longitude float64 `json:"lng" binding:"required"`
}

// ── Trips ─────────────────────────────────────────────────────────────────────

// IncomingTripResponse is what the driver sees when browsing open requests.
// Only trips matching the driver's active vehicle type are returned.
type IncomingTripResponse struct {
	ID              string   `json:"id"`
	RiderID         string   `json:"rider_id"`
	RiderName       string   `json:"rider_name"`
	TripType        string   `json:"trip_type"`
	Note            *string  `json:"note"`
	PickupAddress   string   `json:"pickup_address"`
	DropoffAddress  *string  `json:"dropoff_address"`
	DistanceM       *float64 `json:"distance_m"`
	DefaultFare     float64  `json:"default_fare"`
	VehicleTypeName string   `json:"vehicle_type"`
	PaymentMethod   string   `json:"payment_method"`
	CreatedAt       string   `json:"created_at"`
}

// OfferPriceRequest — driver sets the initial price.
// Floor price (default_fare) is enforced server-side for BOTH transport and errand trips.
type OfferPriceRequest struct {
	OfferedFare float64 `json:"offered_fare" binding:"required,gt=0"`
}

// CounterOfferRequest — driver counter-offers after rider has countered.
// Same floor price rules apply.
type CounterOfferRequest struct {
	OfferedFare float64 `json:"offered_fare" binding:"required,gt=0"`
}

// DriverTripResponse is the full trip detail from the driver's perspective.
type DriverTripResponse struct {
	ID             string   `json:"id"`
	RiderID        string   `json:"rider_id"`
	RiderName      string   `json:"rider_name"`
	RiderPhone     string   `json:"rider_phone"`
	Status         string   `json:"status"`
	TripType       string   `json:"trip_type"`
	Note           *string  `json:"note"`
	PickupAddress  string   `json:"pickup_address"`
	DropoffAddress *string  `json:"dropoff_address"`
	PickupLat      float64  `json:"pickup_lat"`
	PickupLng      float64  `json:"pickup_lng"`
	DropoffLat     *float64 `json:"dropoff_lat"`
	DropoffLng     *float64 `json:"dropoff_lng"`
	OfferedFare    *float64 `json:"offered_fare"`
	Fare           *float64 `json:"fare"`
	PaymentMethod  string   `json:"payment_method"`
	// Negotiation state — used by app to show correct UI
	LastOfferBy        *string `json:"last_offer_by"`
	OfferRound         int     `json:"offer_round"`
	DriverCounterCount int     `json:"driver_counter_count"`
	RiderCounterCount  int     `json:"rider_counter_count"`
	CreatedAt          string  `json:"created_at"`
	UpdatedAt          string  `json:"updated_at"`
	// Rating the driver gave to the rider for this trip (nil = not yet rated)
	RiderRatingGiven *int `json:"rider_rating_given"`
}

// ── Ratings ───────────────────────────────────────────────────────────────────

type RateRiderRequest struct {
	Score   int    `json:"score"   binding:"required,min=1,max=5"`
	Comment string `json:"comment"`
}

// ── Earnings ──────────────────────────────────────────────────────────────────

type EarningsSummary struct {
	TodayTotal   float64  `json:"today_total"`
	TodayTrips   int      `json:"today_trips"`
	WeekTotal    float64  `json:"week_total"`
	WeekTrips    int      `json:"week_trips"`
	MonthTotal   float64  `json:"month_total"`
	MonthTrips   int      `json:"month_trips"`
	AllTimeTotal float64  `json:"all_time_total"`
	AllTimeTrips int      `json:"all_time_trips"`
	AvgRating    *float64 `json:"avg_rating"`
	RatingCount  int      `json:"rating_count"`
}

// DailyEarningResponse is one row in the 7-day earnings chart.
// Days with no completed trips are still returned with Total=0 and Trips=0
// so the chart always has exactly 7 bars.
type DailyEarningResponse struct {
	Date  string  `json:"date"` // "2026-05-03"
	Total float64 `json:"total"`
	Trips int     `json:"trips"`
}
