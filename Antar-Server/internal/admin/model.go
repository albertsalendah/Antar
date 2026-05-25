package admin

// ── Vehicle types ─────────────────────────────────────────────────────────────

type VehicleTypeRequest struct {
	Name        string `json:"name"       binding:"required"`
	Code        string `json:"code"       binding:"required"`
	Description string `json:"description"`
	IsEnabled   *bool  `json:"is_enabled"`
}

type VehicleTypeResponse struct {
	ID          int    `json:"id"`
	Name        string `json:"name"`
	Code        string `json:"code"`
	Description string `json:"description"`
	IsEnabled   bool   `json:"is_enabled"`
}

// ── Fare rules ────────────────────────────────────────────────────────────────

type FareRuleResponse struct {
	ID            int     `json:"id"`
	VehicleTypeID int     `json:"vehicle_type_id"`
	VehicleType   string  `json:"vehicle_type"`
	DefaultFare   float64 `json:"default_fare"`
	Currency      string  `json:"currency"`
	UpdatedAt     string  `json:"updated_at"`
}

type UpdateFareRuleRequest struct {
	DefaultFare float64 `json:"default_fare" binding:"required,gt=0"`
}

// ── Payment methods ───────────────────────────────────────────────────────────

type PaymentMethodResponse struct {
	ID        int    `json:"id"`
	Name      string `json:"name"`
	Code      string `json:"code"`
	IsEnabled bool   `json:"is_enabled"`
}

type TogglePaymentMethodRequest struct {
	IsEnabled bool `json:"is_enabled"`
}

// ── Islands ───────────────────────────────────────────────────────────────────

type IslandResponse struct {
	ID            int    `json:"id"`
	Name          string `json:"name"`
	SearchRadiusM int    `json:"search_radius_m"`
	HasBoundary   bool   `json:"has_boundary"`
}

type UpdateIslandRequest struct {
	SearchRadiusM int    `json:"search_radius_m" binding:"required,gt=0"`
	Name          string `json:"name"`
}

// ── App settings ──────────────────────────────────────────────────────────────

// NegotiationSettingsResponse is returned by GET /admin/settings/negotiation.
type NegotiationSettingsResponse struct {
	MaxNegotiationRounds int `json:"max_negotiation_rounds"`
	// Derived values shown for admin convenience:
	// If rounds = 0 or 1 → countering disabled, only driver sets price.
	// Otherwise rider gets CEIL(rounds/2) counters, driver gets FLOOR(rounds/2).
	RiderCounterMax   int  `json:"rider_counter_max"`
	DriverCounterMax  int  `json:"driver_counter_max"`
	CounteringEnabled bool `json:"countering_enabled"`
}

// UpdateNegotiationSettingsRequest — admin sets max negotiation rounds.
// Set to 0 or 1 to disable countering (driver sets price, rider accepts/rejects only).
type UpdateNegotiationSettingsRequest struct {
	MaxNegotiationRounds int `json:"max_negotiation_rounds" binding:"min=0"`
}
