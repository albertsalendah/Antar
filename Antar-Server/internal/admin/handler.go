package admin

import (
	"antar/pkg/response"
	"context"
	"log/slog"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Handler struct {
	db *pgxpool.Pool
}

func NewHandler(db *pgxpool.Pool) *Handler {
	return &Handler{db: db}
}

// ── Vehicle types ─────────────────────────────────────────────────────────────

func (h *Handler) ListVehicleTypes(c *gin.Context) {
	rows, err := h.db.Query(context.Background(),
		`SELECT id, name, code, description, is_enabled
		 FROM vehicle_types ORDER BY id ASC`)
	if err != nil {
		slog.Error("ListVehicleTypes failed", "error", err)
		response.InternalError(c)
		return
	}
	defer rows.Close()
	types := []VehicleTypeResponse{}
	for rows.Next() {
		var v VehicleTypeResponse
		if err := rows.Scan(&v.ID, &v.Name, &v.Code, &v.Description, &v.IsEnabled); err != nil {
			response.InternalError(c)
			return
		}
		types = append(types, v)
	}
	response.Success(c, types)
}

func (h *Handler) CreateVehicleType(c *gin.Context) {
	var req VehicleTypeRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	enabled := true
	if req.IsEnabled != nil {
		enabled = *req.IsEnabled
	}
	var id int
	err := h.db.QueryRow(context.Background(),
		`INSERT INTO vehicle_types (name, code, description, is_enabled)
		 VALUES ($1, $2, $3, $4) RETURNING id`,
		req.Name, req.Code, req.Description, enabled,
	).Scan(&id)
	if err != nil {
		slog.Error("CreateVehicleType failed", "error", err)
		response.InternalError(c)
		return
	}
	// Auto-seed Rp.2000 fare rule for the new type
	_, _ = h.db.Exec(context.Background(),
		`INSERT INTO fare_rules (vehicle_type_id, default_fare, currency)
		 VALUES ($1, 2000, 'IDR') ON CONFLICT (vehicle_type_id) DO NOTHING`, id,
	)
	response.Created(c, gin.H{"id": id, "message": "Vehicle type created"})
}

func (h *Handler) UpdateVehicleType(c *gin.Context) {
	typeID := c.Param("type_id")
	var req VehicleTypeRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	enabled := true
	if req.IsEnabled != nil {
		enabled = *req.IsEnabled
	}
	result, err := h.db.Exec(context.Background(),
		`UPDATE vehicle_types
		 SET name        = COALESCE(NULLIF($1,''), name),
		     code        = COALESCE(NULLIF($2,''), code),
		     description = COALESCE(NULLIF($3,''), description),
		     is_enabled  = $4
		 WHERE id = $5`,
		req.Name, req.Code, req.Description, enabled, typeID,
	)
	if err != nil {
		slog.Error("UpdateVehicleType failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.NotFound(c, "Vehicle type not found")
		return
	}
	response.Success(c, gin.H{"message": "Vehicle type updated"})
}

// ── Fare rules ────────────────────────────────────────────────────────────────

func (h *Handler) ListFareRules(c *gin.Context) {
	rows, err := h.db.Query(context.Background(),
		`SELECT fr.id, fr.vehicle_type_id, vt.name,
		        fr.default_fare, fr.currency, fr.updated_at
		 FROM fare_rules fr
		 JOIN vehicle_types vt ON vt.id = fr.vehicle_type_id
		 ORDER BY fr.vehicle_type_id ASC`)
	if err != nil {
		slog.Error("ListFareRules failed", "error", err)
		response.InternalError(c)
		return
	}
	defer rows.Close()
	rules := []FareRuleResponse{}
	for rows.Next() {
		var r FareRuleResponse
		if err := rows.Scan(
			&r.ID, &r.VehicleTypeID, &r.VehicleType,
			&r.DefaultFare, &r.Currency, &r.UpdatedAt,
		); err != nil {
			response.InternalError(c)
			return
		}
		rules = append(rules, r)
	}
	response.Success(c, rules)
}

// UpdateFareRule handles PATCH /api/v1/admin/fare-rules/:type_id
// Sets the flat floor price for a vehicle type.
// Both transport and errand trips enforce this floor — drivers cannot offer below it.
func (h *Handler) UpdateFareRule(c *gin.Context) {
	typeID := c.Param("type_id")
	var req UpdateFareRuleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	result, err := h.db.Exec(context.Background(),
		`UPDATE fare_rules SET default_fare = $1, updated_at = $2
		 WHERE vehicle_type_id = $3`,
		req.DefaultFare, time.Now(), typeID,
	)
	if err != nil {
		slog.Error("UpdateFareRule failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.NotFound(c, "No fare rule found for this vehicle type")
		return
	}
	response.Success(c, gin.H{"message": "Fare rule updated", "default_fare": req.DefaultFare})
}

// ── Payment methods ───────────────────────────────────────────────────────────

func (h *Handler) ListPaymentMethods(c *gin.Context) {
	rows, err := h.db.Query(context.Background(),
		`SELECT id, name, code, is_enabled FROM payment_methods ORDER BY id ASC`)
	if err != nil {
		slog.Error("ListPaymentMethods failed", "error", err)
		response.InternalError(c)
		return
	}
	defer rows.Close()
	methods := []PaymentMethodResponse{}
	for rows.Next() {
		var m PaymentMethodResponse
		if err := rows.Scan(&m.ID, &m.Name, &m.Code, &m.IsEnabled); err != nil {
			response.InternalError(c)
			return
		}
		methods = append(methods, m)
	}
	response.Success(c, methods)
}

// TogglePaymentMethod handles PATCH /api/v1/admin/payment-methods/:method_id
// Cash cannot be disabled — it is always the default payment method.
func (h *Handler) TogglePaymentMethod(c *gin.Context) {
	methodID := c.Param("method_id")
	var req TogglePaymentMethodRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	var code string
	h.db.QueryRow(context.Background(),
		`SELECT code FROM payment_methods WHERE id = $1`, methodID,
	).Scan(&code)
	if code == "cash" && !req.IsEnabled {
		response.BadRequest(c, "Cash payment cannot be disabled — it is the default payment method")
		return
	}
	result, err := h.db.Exec(context.Background(),
		`UPDATE payment_methods SET is_enabled = $1 WHERE id = $2`,
		req.IsEnabled, methodID,
	)
	if err != nil {
		slog.Error("TogglePaymentMethod failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.NotFound(c, "Payment method not found")
		return
	}
	status := "disabled"
	if req.IsEnabled {
		status = "enabled"
	}
	response.Success(c, gin.H{"message": "Payment method " + status})
}

// ── Islands ───────────────────────────────────────────────────────────────────

func (h *Handler) ListIslands(c *gin.Context) {
	rows, err := h.db.Query(context.Background(),
		`SELECT id, name, search_radius_m,
		        (boundary IS NOT NULL) AS has_boundary
		 FROM islands ORDER BY id ASC`)
	if err != nil {
		slog.Error("ListIslands failed", "error", err)
		response.InternalError(c)
		return
	}
	defer rows.Close()
	islands := []IslandResponse{}
	for rows.Next() {
		var i IslandResponse
		if err := rows.Scan(&i.ID, &i.Name, &i.SearchRadiusM, &i.HasBoundary); err != nil {
			response.InternalError(c)
			return
		}
		islands = append(islands, i)
	}
	response.Success(c, islands)
}

// UpdateIsland handles PATCH /api/v1/admin/islands/:island_id
func (h *Handler) UpdateIsland(c *gin.Context) {
	islandID := c.Param("island_id")
	var req UpdateIslandRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	result, err := h.db.Exec(context.Background(),
		`UPDATE islands
		 SET search_radius_m = $1,
		     name            = CASE WHEN $2 <> '' THEN $2 ELSE name END
		 WHERE id = $3`,
		req.SearchRadiusM, req.Name, islandID,
	)
	if err != nil {
		slog.Error("UpdateIsland failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.NotFound(c, "Island not found")
		return
	}
	response.Success(c, gin.H{
		"message":         "Island updated",
		"search_radius_m": req.SearchRadiusM,
	})
}

// ── App settings — negotiation ────────────────────────────────────────────────

// GetNegotiationSettings handles GET /api/v1/admin/settings/negotiation
// Returns the current max negotiation rounds and derived per-side limits.
func (h *Handler) GetNegotiationSettings(c *gin.Context) {
	var maxRounds int
	err := h.db.QueryRow(context.Background(),
		`SELECT value::int FROM app_settings WHERE key = 'max_negotiation_rounds'`,
	).Scan(&maxRounds)
	if err != nil {
		slog.Error("GetNegotiationSettings failed", "error", err)
		response.InternalError(c)
		return
	}
	response.Success(c, buildNegotiationResponse(maxRounds))
}

// UpdateNegotiationSettings handles PATCH /api/v1/admin/settings/negotiation
// Set max_negotiation_rounds to 0 or 1 to disable countering entirely —
// only the driver will set the price and the rider accepts or rejects.
// For values >= 2 the rider gets CEIL(n/2) counters and the driver FLOOR(n/2).
func (h *Handler) UpdateNegotiationSettings(c *gin.Context) {
	var req UpdateNegotiationSettingsRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	_, err := h.db.Exec(context.Background(),
		`INSERT INTO app_settings (key, value, updated_at)
		 VALUES ('max_negotiation_rounds', $1::text, $2)
		 ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at`,
		req.MaxNegotiationRounds, time.Now(),
	)
	if err != nil {
		slog.Error("UpdateNegotiationSettings failed", "error", err)
		response.InternalError(c)
		return
	}
	response.Success(c, buildNegotiationResponse(req.MaxNegotiationRounds))
}

// buildNegotiationResponse computes derived per-side limits from the max rounds value.
func buildNegotiationResponse(maxRounds int) NegotiationSettingsResponse {
	if maxRounds <= 1 {
		return NegotiationSettingsResponse{
			MaxNegotiationRounds: maxRounds,
			RiderCounterMax:      0,
			DriverCounterMax:     0,
			CounteringEnabled:    false,
		}
	}
	riderMax := (maxRounds + 1) / 2 // CEIL(maxRounds / 2)
	driverMax := maxRounds / 2      // FLOOR(maxRounds / 2)
	return NegotiationSettingsResponse{
		MaxNegotiationRounds: maxRounds,
		RiderCounterMax:      riderMax,
		DriverCounterMax:     driverMax,
		CounteringEnabled:    true,
	}
}
