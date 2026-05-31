package driver

import (
	"antar/pkg/fcm"
	"antar/pkg/response"
	"antar/pkg/supabase"
	"context"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Handler struct {
	db       *pgxpool.Pool
	supabase *supabase.Client
	fcm      *fcm.Client
}

func NewHandler(db *pgxpool.Pool, sb *supabase.Client, fcmClient *fcm.Client) *Handler {
	return &Handler{db: db, supabase: sb, fcm: fcmClient}
}

// ── Auth ──────────────────────────────────────────────────────────────────────

func (h *Handler) Register(c *gin.Context) {
	var req RegisterRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	result, err := h.supabase.SignUp(req.Email, req.Password)
	if err != nil {
		slog.Error("Supabase SignUp failed", "error", err)
		response.BadRequest(c, err.Error())
		return
	}
	_, err = h.db.Exec(context.Background(),
		`INSERT INTO driver_profiles (id, full_name, phone_number, email, created_at, updated_at)
		 VALUES ($1, $2, $3, $4, $5, $5) ON CONFLICT (id) DO NOTHING`,
		result.UserID, req.FullName, req.PhoneNumber, req.Email, time.Now(),
	)
	if err != nil {
		slog.Error("Failed to insert driver_profile", "user_id", result.UserID, "error", err)
		response.InternalError(c)
		return
	}
	if result.NeedsConfirmation {
		c.JSON(202, gin.H{"needs_confirmation": true,
			"message": "Account created! Please check your email and confirm before logging in."})
		return
	}
	response.Created(c, gin.H{
		"needs_confirmation": false,
		"access_token":       result.AccessToken,
		"refresh_token":      result.RefreshToken,
		"driver_id":          result.UserID,
	})
}

func (h *Handler) Login(c *gin.Context) {
	var req LoginRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	authResp, err := h.supabase.SignIn(req.Email, req.Password)
	if err != nil {
		response.Unauthorized(c, err.Error())
		return
	}
	var fullName string
	h.db.QueryRow(context.Background(),
		`SELECT full_name FROM driver_profiles WHERE id = $1`, authResp.User.ID,
	).Scan(&fullName)
	response.Success(c, gin.H{
		"access_token":  authResp.AccessToken,
		"refresh_token": authResp.RefreshToken,
		"driver_id":     authResp.User.ID,
		"full_name":     fullName,
	})
}

func (h *Handler) RefreshToken(c *gin.Context) {
	var req struct {
		RefreshToken string `json:"refresh_token" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	authResp, err := h.supabase.RefreshSession(req.RefreshToken)
	if err != nil {
		slog.Warn("Token refresh failed", "error", err)
		response.Unauthorized(c, "Session expired — please log in again")
		return
	}
	var fullName string
	h.db.QueryRow(context.Background(),
		`SELECT full_name FROM driver_profiles WHERE id = $1`, authResp.User.ID,
	).Scan(&fullName)
	response.Success(c, gin.H{
		"access_token":  authResp.AccessToken,
		"refresh_token": authResp.RefreshToken,
		"driver_id":     authResp.User.ID,
		"full_name":     fullName,
	})
}

// ── Device ────────────────────────────────────────────────────────────────────

func (h *Handler) SaveFCMToken(c *gin.Context) {
	driverID, _ := c.Get("userID")
	var req FCMTokenRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	_, err := h.db.Exec(context.Background(),
		`UPDATE driver_profiles SET fcm_token = $1, updated_at = $2 WHERE id = $3`,
		req.Token, time.Now(), driverID,
	)
	if err != nil {
		slog.Error("SaveFCMToken failed", "error", err)
		response.InternalError(c)
		return
	}
	response.Success(c, gin.H{"message": "FCM token saved"})
}

// ── Profile ───────────────────────────────────────────────────────────────────

func (h *Handler) GetProfile(c *gin.Context) {
	driverID, _ := c.Get("userID")
	var p ProfileResponse
	err := h.db.QueryRow(context.Background(),
		`SELECT dp.id, dp.full_name, dp.phone_number, COALESCE(dp.email,''),
		        dp.avatar_url, dp.is_online, dp.active_vehicle_id,
		        dp.avg_rating, dp.rating_count,
		        dp.island_id, i.name
		 FROM driver_profiles dp
		 LEFT JOIN islands i ON i.id = dp.island_id
		 WHERE dp.id = $1`,
		driverID,
	).Scan(
		&p.ID, &p.FullName, &p.PhoneNumber, &p.Email,
		&p.AvatarURL, &p.IsOnline, &p.ActiveVehicleID,
		&p.AvgRating, &p.RatingCount,
		&p.IslandID, &p.IslandName, // ← new
	)
	if err != nil {
		slog.Error("GetProfile failed", "driver_id", driverID, "error", err)
		response.NotFound(c, "Driver profile not found")
		return
	}
	response.Success(c, p)
}

func (h *Handler) UpdateProfile(c *gin.Context) {
	driverID, _ := c.Get("userID")
	var req UpdateProfileRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	_, err := h.db.Exec(context.Background(),
		`UPDATE driver_profiles
		 SET full_name  = CASE WHEN $1 <> '' THEN $1 ELSE full_name END,
		     email      = COALESCE($2, email),
		     updated_at = $3
		 WHERE id = $4`,
		req.FullName, req.Email, time.Now(), driverID,
	)
	if err != nil {
		slog.Error("UpdateProfile failed", "error", err)
		response.InternalError(c)
		return
	}
	response.Success(c, gin.H{"message": "Profile updated"})
}

func (h *Handler) UploadAvatar(c *gin.Context) {
	driverID, _ := c.Get("userID")
	authHeader := c.GetHeader("Authorization")
	userJWT := strings.TrimPrefix(authHeader, "Bearer ")
	if userJWT == "" {
		response.Unauthorized(c, "Missing token")
		return
	}
	file, header, err := c.Request.FormFile("avatar")
	if err != nil {
		response.BadRequest(c, "Missing 'avatar' file field")
		return
	}
	defer file.Close()
	contentType := header.Header.Get("Content-Type")
	if contentType == "" {
		contentType = "image/jpeg"
	}
	if header.Size > 2<<20 {
		response.BadRequest(c, "Image too large (max 2 MB)")
		return
	}
	imageBytes, err := io.ReadAll(file)
	if err != nil {
		slog.Error("Failed to read avatar bytes", "error", err)
		response.InternalError(c)
		return
	}
	avatarURL, err := h.supabase.UploadAvatar(driverID.(string), userJWT, imageBytes, contentType)
	if err != nil {
		slog.Error("Supabase avatar upload failed", "driver_id", driverID, "error", err)
		response.InternalError(c)
		return
	}
	_, err = h.db.Exec(context.Background(),
		`UPDATE driver_profiles SET avatar_url = $1, updated_at = $2 WHERE id = $3`,
		avatarURL, time.Now(), driverID,
	)
	if err != nil {
		slog.Error("Failed to save avatar_url", "error", err)
		response.InternalError(c)
		return
	}
	response.Success(c, gin.H{"avatar_url": avatarURL})
}

// ── Vehicle types ─────────────────────────────────────────────────────────────

func (h *Handler) GetVehicleTypes(c *gin.Context) {
	rows, err := h.db.Query(context.Background(),
		`SELECT id, name, code, description FROM vehicle_types
		 WHERE is_enabled = true ORDER BY id DESC`)
	if err != nil {
		slog.Error("GetVehicleTypes failed", "error", err)
		response.InternalError(c)
		return
	}
	defer rows.Close()
	types := []VehicleType{}
	for rows.Next() {
		var vt VehicleType
		if err := rows.Scan(&vt.ID, &vt.Name, &vt.Code, &vt.Description); err != nil {
			response.InternalError(c)
			return
		}
		types = append(types, vt)
	}
	response.Success(c, types)
}

// ── Vehicles ──────────────────────────────────────────────────────────────────

func (h *Handler) AddVehicle(c *gin.Context) {
	var req AddVehicleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	driverID, _ := c.Get("userID")
	var typeExists bool
	h.db.QueryRow(context.Background(),
		`SELECT EXISTS(SELECT 1 FROM vehicle_types WHERE id=$1 AND is_enabled=true)`,
		req.VehicleTypeID,
	).Scan(&typeExists)
	if !typeExists {
		response.BadRequest(c, "Invalid or disabled vehicle type")
		return
	}
	var vehicleID string
	err := h.db.QueryRow(context.Background(),
		`INSERT INTO driver_vehicles
		   (driver_id,vehicle_type_id,license_plate,make,model,year,color,created_at,updated_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$8) RETURNING id`,
		driverID, req.VehicleTypeID, req.LicensePlate,
		req.Make, req.Model, req.Year, req.Color, time.Now(),
	).Scan(&vehicleID)
	if err != nil {
		slog.Error("AddVehicle failed", "error", err)
		response.InternalError(c)
		return
	}
	response.Created(c, gin.H{"message": "Vehicle added successfully", "vehicle_id": vehicleID})
}

func (h *Handler) ListVehicles(c *gin.Context) {
	driverID, _ := c.Get("userID")
	rows, err := h.db.Query(context.Background(),
		`SELECT dv.id,dv.vehicle_type_id,vt.name,
		        dv.license_plate,dv.make,dv.model,dv.year,dv.color,dv.is_active
		 FROM driver_vehicles dv
		 JOIN vehicle_types vt ON vt.id=dv.vehicle_type_id
		 WHERE dv.driver_id=$1 ORDER BY dv.created_at ASC`,
		driverID,
	)
	if err != nil {
		slog.Error("ListVehicles failed", "error", err)
		response.InternalError(c)
		return
	}
	defer rows.Close()
	vehicles := []VehicleResponse{}
	for rows.Next() {
		var v VehicleResponse
		if err := rows.Scan(&v.ID, &v.VehicleTypeID, &v.VehicleType,
			&v.LicensePlate, &v.Make, &v.Model, &v.Year, &v.Color, &v.IsActive,
		); err != nil {
			response.InternalError(c)
			return
		}
		vehicles = append(vehicles, v)
	}
	response.Success(c, vehicles)
}

func (h *Handler) UpdateVehicle(c *gin.Context) {
	vehicleID := c.Param("vehicle_id")
	driverID, _ := c.Get("userID")
	var req UpdateVehicleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	result, err := h.db.Exec(context.Background(),
		`UPDATE driver_vehicles
		 SET make=COALESCE(NULLIF($1,''),make),model=COALESCE(NULLIF($2,''),model),
		     year=CASE WHEN $3>0 THEN $3 ELSE year END,
		     color=COALESCE(NULLIF($4,''),color),is_active=COALESCE($5,is_active),
		     updated_at=$6
		 WHERE id=$7 AND driver_id=$8`,
		req.Make, req.Model, req.Year, req.Color, req.IsActive,
		time.Now(), vehicleID, driverID,
	)
	if err != nil {
		slog.Error("UpdateVehicle failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.NotFound(c, "Vehicle not found")
		return
	}
	response.Success(c, gin.H{"message": "Vehicle updated successfully"})
}

func (h *Handler) DeleteVehicle(c *gin.Context) {
	vehicleID := c.Param("vehicle_id")
	driverID, _ := c.Get("userID")
	result, err := h.db.Exec(context.Background(),
		`DELETE FROM driver_vehicles WHERE id=$1 AND driver_id=$2`, vehicleID, driverID,
	)
	if err != nil {
		slog.Error("DeleteVehicle failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.NotFound(c, "Vehicle not found")
		return
	}
	response.Success(c, gin.H{"message": "Vehicle deleted successfully"})
}

func (h *Handler) SetActiveVehicle(c *gin.Context) {
	vehicleID := c.Param("vehicle_id")
	driverID, _ := c.Get("userID")
	var ok bool
	h.db.QueryRow(context.Background(),
		`SELECT EXISTS(SELECT 1 FROM driver_vehicles
		  WHERE id=$1 AND driver_id=$2 AND is_active=true)`,
		vehicleID, driverID,
	).Scan(&ok)
	if !ok {
		response.BadRequest(c, "Vehicle not found or is currently disabled")
		return
	}
	_, err := h.db.Exec(context.Background(),
		`UPDATE driver_profiles SET active_vehicle_id=$1 WHERE id=$2`, vehicleID, driverID,
	)
	if err != nil {
		slog.Error("SetActiveVehicle failed", "error", err)
		response.InternalError(c)
		return
	}
	response.Success(c, gin.H{"message": "Active vehicle updated"})
}

// ── Location ──────────────────────────────────────────────────────────────────

func (h *Handler) UpdateLocation(c *gin.Context) {
	var req UpdateLocationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	if req.Latitude < -90 || req.Latitude > 90 {
		response.BadRequest(c, "Latitude must be between -90 and 90")
		return
	}
	if req.Longitude < -180 || req.Longitude > 180 {
		response.BadRequest(c, "Longitude must be between -180 and 180")
		return
	}
	driverID, _ := c.Get("userID")
	var hasActiveVehicle bool
	h.db.QueryRow(context.Background(),
		`SELECT EXISTS(SELECT 1 FROM driver_profiles
		  WHERE id=$1 AND active_vehicle_id IS NOT NULL)`, driverID,
	).Scan(&hasActiveVehicle)
	if !hasActiveVehicle {
		response.BadRequest(c, "Please select an active vehicle before going online")
		return
	}
	result, err := h.db.Exec(context.Background(),
		`UPDATE driver_profiles
     SET last_location = ST_SetSRID(ST_MakePoint($1,$2),4326),
         last_lat      = $2,
         last_lng      = $1,
         island_id     = resolve_island_id($1, $2),
         updated_at    = $3,
         is_online     = true
     WHERE id = $4`,
		req.Longitude, req.Latitude, time.Now(), driverID,
	)
	if err != nil {
		slog.Error("UpdateLocation failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.NotFound(c, "Driver profile not found")
		return
	}
	response.Success(c, gin.H{"message": "Location updated"})
}

func (h *Handler) GoOffline(c *gin.Context) {
	driverID, _ := c.Get("userID")
	_, err := h.db.Exec(context.Background(),
		`UPDATE driver_profiles SET is_online=false WHERE id=$1`, driverID,
	)
	if err != nil {
		slog.Error("GoOffline failed", "error", err)
		response.InternalError(c)
		return
	}
	response.Success(c, gin.H{"message": "You are now offline"})
}

// ── Trips ─────────────────────────────────────────────────────────────────────

// IncomingTrips handles GET /api/v1/driver/trips/incoming
// Returns open (requested) trips that match the driver's active vehicle type
// and are on the same island. If the driver has no active vehicle set,
// an empty list is returned rather than an error.
func (h *Handler) IncomingTrips(c *gin.Context) {
	driverID, _ := c.Get("userID")

	var islandID int
	var driverLng, driverLat float64
	var activeVehicleTypeID *int
	err := h.db.QueryRow(context.Background(),
		`SELECT COALESCE(dp.island_id, 0),
		        COALESCE(ST_X(dp.last_location::geometry), 0),
		        COALESCE(ST_Y(dp.last_location::geometry), 0),
		        dv.vehicle_type_id
		 FROM driver_profiles dp
		 LEFT JOIN driver_vehicles dv ON dv.id = dp.active_vehicle_id
		 WHERE dp.id = $1`,
		driverID,
	).Scan(&islandID, &driverLng, &driverLat, &activeVehicleTypeID)
	if err != nil || islandID == 0 {
		response.BadRequest(c, "Your location is not set — please update your location first")
		return
	}
	if activeVehicleTypeID == nil {
		response.BadRequest(c, "Please select an active vehicle before browsing trips")
		return
	}

	rows, err := h.db.Query(context.Background(),
		`SELECT t.id, t.rider_id,
		        COALESCE(rp.full_name,'') AS rider_name,
		        t.trip_type::text, t.note,
		        t.pickup_address, t.dropoff_address,
		        ST_Distance(t.pickup_location, ST_SetSRID(ST_MakePoint($1,$2),4326)) AS distance_m,
		        COALESCE(fr.default_fare, 2000) AS default_fare,
		        COALESCE(vt.name,'') AS vehicle_type_name,
		        COALESCE(pm.code,'cash'),
		        t.created_at::text
		 FROM trips t
		 LEFT JOIN rider_profiles  rp ON rp.id = t.rider_id
		 LEFT JOIN vehicle_types   vt ON vt.id = t.vehicle_type_id
		 LEFT JOIN fare_rules      fr ON fr.vehicle_type_id = t.vehicle_type_id
		 LEFT JOIN payment_methods pm ON pm.id = t.payment_method_id
		 WHERE t.status          = 'requested'
		   AND t.island_id       = $3
		   AND t.vehicle_type_id = $4
		 ORDER BY t.created_at ASC`,
		driverLng, driverLat, islandID, activeVehicleTypeID,
	)
	if err != nil {
		slog.Error("IncomingTrips query failed", "error", err)
		// response.InternalError(c)
		response.BadRequest(c, err.Error())
		return
	}
	defer rows.Close()

	trips := []IncomingTripResponse{}
	for rows.Next() {
		var t IncomingTripResponse
		if err := rows.Scan(
			&t.ID, &t.RiderID, &t.RiderName,
			&t.TripType, &t.Note,
			&t.PickupAddress, &t.DropoffAddress,
			&t.DistanceM, &t.DefaultFare, &t.VehicleTypeName,
			&t.PaymentMethod, &t.CreatedAt,
		); err != nil {
			slog.Error("IncomingTrips scan failed", "error", err)
			// response.InternalError(c)
			response.BadRequest(c, err.Error())
			return
		}
		trips = append(trips, t)
	}
	response.Success(c, trips)
}

// OfferPrice handles POST /api/v1/driver/trips/:trip_id/offer
//
// Makes the initial price offer. Atomically locks the trip.
// Floor price enforced for BOTH transport and errand trips.
// Resets negotiation counters so each driver-rider pair starts fresh.
func (h *Handler) OfferPrice(c *gin.Context) {
	tripID := c.Param("trip_id")
	driverID, _ := c.Get("userID")

	var req OfferPriceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	// Get default fare for this trip's vehicle type
	var defaultFare float64
	err := h.db.QueryRow(context.Background(),
		`SELECT COALESCE(fr.default_fare, 0)
		 FROM trips t
		 LEFT JOIN fare_rules fr ON fr.vehicle_type_id = t.vehicle_type_id
		 WHERE t.id = $1 AND t.status = 'requested'`,
		tripID,
	).Scan(&defaultFare)
	if err != nil {
		response.NotFound(c, "Trip not found or no longer available")
		return
	}

	// Floor price applies to ALL trip types
	if req.OfferedFare < defaultFare {
		response.BadRequest(c, fmt.Sprintf(
			"Offered fare must be at least the minimum fare of Rp %.0f", defaultFare))
		return
	}

	// Atomic lock — only succeeds if status is still 'requested'
	// Resets negotiation counters for this fresh driver-rider pair
	var riderID string
	err = h.db.QueryRow(context.Background(),
		`UPDATE trips
		 SET status               = 'offered',
		     offered_by           = $1,
		     offered_fare         = $2,
		     last_offer_by        = 'driver',
		     offer_round          = 1,
		     driver_counter_count = 0,
		     rider_counter_count  = 0,
		     updated_at           = $3
		 WHERE id = $4 AND status = 'requested'
		 RETURNING rider_id`,
		driverID, req.OfferedFare, time.Now(), tripID,
	).Scan(&riderID)
	if err != nil {
		response.BadRequest(c, "Trip is no longer available — another driver may have offered first")
		return
	}

	// Notify rider via FCM (fire-and-forget)
	go func() {
		var riderToken string
		h.db.QueryRow(context.Background(),
			`SELECT COALESCE(fcm_token,'') FROM rider_profiles WHERE id = $1`, riderID,
		).Scan(&riderToken)
		if riderToken != "" {
			if err := h.fcm.Send(context.Background(), fcm.Message{
				Token: riderToken,
				Notification: &fcm.Notification{
					Title: "Ada Penawaran Harga!",
					Body:  fmt.Sprintf("Driver menawarkan harga Rp %.0f", req.OfferedFare),
				},
				Data: map[string]string{
					"type":         "driver_offer",
					"trip_id":      tripID,
					"offered_fare": fmt.Sprintf("%.0f", req.OfferedFare),
				},
			}); err != nil {
				slog.Warn("FCM notify rider of offer failed", "error", err)
			}
		}
	}()

	response.Success(c, gin.H{
		"message":      "Offer submitted — waiting for rider to accept",
		"offered_fare": req.OfferedFare,
	})
}

// CounterOffer handles POST /api/v1/driver/trips/:trip_id/counter
//
// Driver counters after the rider has submitted a counter-offer.
// Rules:
//   - Trip must be in 'offered' status with last_offer_by = 'rider'
//   - driver_counter_count must be < FLOOR(max_negotiation_rounds / 2)
//   - Offered fare must be >= default_fare (floor enforced for all trip types)
//   - If driver has exhausted their counters, they can only accept or reject
func (h *Handler) CounterOffer(c *gin.Context) {
	tripID := c.Param("trip_id")
	driverID, _ := c.Get("userID")

	var req CounterOfferRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	// Load trip state and max rounds in one query
	var riderID string
	var defaultFare float64
	var driverCounterCount, maxRounds int
	err := h.db.QueryRow(context.Background(),
		`SELECT t.rider_id,
		        COALESCE(fr.default_fare, 0),
		        t.driver_counter_count,
		        (SELECT value::int FROM app_settings WHERE key = 'max_negotiation_rounds')
		 FROM trips t
		 LEFT JOIN fare_rules fr ON fr.vehicle_type_id = t.vehicle_type_id
		 WHERE t.id = $1
		   AND (t.driver_id = $2 OR t.offered_by = $2)
		   AND t.status = 'offered'
		   AND t.last_offer_by = 'rider'`,
		tripID, driverID,
	).Scan(&riderID, &defaultFare, &driverCounterCount, &maxRounds)
	if err != nil {
		response.BadRequest(c, "No rider counter-offer found for this trip, or it is not your trip")
		return
	}

	// Check driver still has counter attempts
	driverCounterMax := maxRounds / 2 // FLOOR
	if driverCounterCount >= driverCounterMax {
		response.BadRequest(c, fmt.Sprintf(
			"You have used all %d counter-offer attempts — please accept or reject the rider's offer",
			driverCounterMax))
		return
	}

	// Floor price applies
	if req.OfferedFare < defaultFare {
		response.BadRequest(c, fmt.Sprintf(
			"Counter-offer must be at least the minimum fare of Rp %.0f", defaultFare))
		return
	}

	// Apply counter
	result, err := h.db.Exec(context.Background(),
		`UPDATE trips
		 SET offered_fare         = $1,
		     last_offer_by        = 'driver',
		     driver_counter_count = driver_counter_count + 1,
		     offer_round          = offer_round + 1,
		     updated_at           = $2
		 WHERE id = $3
		   AND (driver_id = $4 OR offered_by = $4)
		   AND status = 'offered'
		   AND last_offer_by = 'rider'`,
		req.OfferedFare, time.Now(), tripID, driverID,
	)
	if err != nil {
		slog.Error("CounterOffer (driver) failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.BadRequest(c, "Counter-offer could not be applied — trip state may have changed")
		return
	}

	// Notify rider (fire-and-forget)
	go func() {
		var riderToken string
		h.db.QueryRow(context.Background(),
			`SELECT COALESCE(fcm_token,'') FROM rider_profiles WHERE id = $1`, riderID,
		).Scan(&riderToken)
		if riderToken != "" {
			if err := h.fcm.Send(context.Background(), fcm.Message{
				Token: riderToken,
				Notification: &fcm.Notification{
					Title: "Driver Balik Menawar!",
					Body:  fmt.Sprintf("Driver menawarkan harga baru Rp %.0f", req.OfferedFare),
				},
				Data: map[string]string{
					"type":         "driver_counter",
					"trip_id":      tripID,
					"offered_fare": fmt.Sprintf("%.0f", req.OfferedFare),
				},
			}); err != nil {
				slog.Warn("FCM notify rider of driver counter failed", "error", err)
			}
		}
	}()

	response.Success(c, gin.H{
		"message":      "Counter-offer submitted — waiting for rider",
		"offered_fare": req.OfferedFare,
	})
}

func (h *Handler) StartTrip(c *gin.Context) {
	tripID := c.Param("trip_id")
	driverID, _ := c.Get("userID")
	result, err := h.db.Exec(context.Background(),
		`UPDATE trips SET status = 'in_progress', updated_at = $1
		 WHERE id = $2 AND driver_id = $3 AND status = 'arrived'`,
		time.Now(), tripID, driverID,
	)
	if err != nil {
		slog.Error("StartTrip failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.BadRequest(c, "Trip cannot be started — must be in 'agreed' status and assigned to you")
		return
	}
	response.Success(c, gin.H{"message": "Trip started"})
}

func (h *Handler) ArriveAtPickup(c *gin.Context) {
	tripID := c.Param("trip_id")
	driverID, _ := c.Get("userID")

	var riderID string
	err := h.db.QueryRow(context.Background(),
		`UPDATE trips SET status = 'arrived', updated_at = $1
         WHERE id = $2 AND driver_id = $3 AND status = 'agreed'
         RETURNING rider_id`,
		time.Now(), tripID, driverID,
	).Scan(&riderID)
	if err != nil {
		response.BadRequest(c, "Trip cannot be marked arrived — must be 'agreed' and assigned to you")
		return
	}

	go func() {
		var riderToken string
		h.db.QueryRow(context.Background(),
			`SELECT COALESCE(fcm_token,'') FROM rider_profiles WHERE id = $1`, riderID,
		).Scan(&riderToken)
		if riderToken != "" {
			if err := h.fcm.Send(context.Background(), fcm.Message{
				Token: riderToken,
				Notification: &fcm.Notification{
					Title: "Driver Sudah Tiba! 🚗",
					Body:  "Driver Anda sudah tiba di lokasi penjemputan",
				},
				Data: map[string]string{
					"type":    "driver_arrived",
					"trip_id": tripID,
				},
			}); err != nil {
				slog.Warn("FCM notify rider of arrival failed", "error", err)
			}
		}
	}()

	response.Success(c, gin.H{"message": "Marked as arrived"})
}

func (h *Handler) CompleteTrip(c *gin.Context) {
	tripID := c.Param("trip_id")
	driverID, _ := c.Get("userID")
	result, err := h.db.Exec(context.Background(),
		`UPDATE trips SET status = 'completed', updated_at = $1
		 WHERE id = $2 AND driver_id = $3 AND status = 'in_progress'`,
		time.Now(), tripID, driverID,
	)
	if err != nil {
		slog.Error("CompleteTrip failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.BadRequest(c, "Trip cannot be completed — must be in_progress and assigned to you")
		return
	}
	response.Success(c, gin.H{"message": "Trip completed"})
}

func (h *Handler) CancelTrip(c *gin.Context) {
	tripID := c.Param("trip_id")
	driverID, _ := c.Get("userID")
	result, err := h.db.Exec(context.Background(),
		`UPDATE trips
		 SET status    = 'cancelled', driver_id = NULL, fare = NULL, updated_at = $1
		 WHERE id = $2 AND driver_id = $3 AND status = 'agreed'`,
		time.Now(), tripID, driverID,
	)
	if err != nil {
		slog.Error("CancelTrip (driver) failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.BadRequest(c, "Trip cannot be cancelled — must be 'agreed' and assigned to you")
		return
	}
	response.Success(c, gin.H{"message": "Trip cancelled"})
}

func (h *Handler) GetActiveTrip(c *gin.Context) {
	driverID, _ := c.Get("userID")
	var t DriverTripResponse
	err := h.db.QueryRow(context.Background(),
		`SELECT t.id, t.rider_id,
		        COALESCE(rp.full_name,''), COALESCE(rp.phone_number,''),
		        t.status::text, t.trip_type::text, t.note,
		        t.pickup_address, t.dropoff_address,
		        ST_Y(t.pickup_location::geometry), ST_X(t.pickup_location::geometry),
		        CASE WHEN t.dropoff_location IS NOT NULL THEN ST_Y(t.dropoff_location::geometry) END,
		        CASE WHEN t.dropoff_location IS NOT NULL THEN ST_X(t.dropoff_location::geometry) END,
		        t.offered_fare, t.fare,
		        COALESCE(pm.code,'cash'),
		        t.last_offer_by, t.offer_round,
		        t.driver_counter_count, t.rider_counter_count,
		        t.created_at::text, t.updated_at::text,
		        (SELECT r.score FROM ratings r
		         WHERE r.trip_id = t.id AND r.rater_role = 'driver' LIMIT 1)
		 FROM trips t
		 LEFT JOIN rider_profiles  rp ON rp.id = t.rider_id
		 LEFT JOIN payment_methods pm ON pm.id = t.payment_method_id
		 WHERE (t.driver_id = $1 OR t.offered_by = $1)
		   AND t.status IN ('offered','agreed','in_progress')
		 ORDER BY t.updated_at DESC LIMIT 1`,
		driverID,
	).Scan(
		&t.ID, &t.RiderID, &t.RiderName, &t.RiderPhone,
		&t.Status, &t.TripType, &t.Note,
		&t.PickupAddress, &t.DropoffAddress,
		&t.PickupLat, &t.PickupLng,
		&t.DropoffLat, &t.DropoffLng,
		&t.OfferedFare, &t.Fare, &t.PaymentMethod,
		&t.LastOfferBy, &t.OfferRound,
		&t.DriverCounterCount, &t.RiderCounterCount,
		&t.CreatedAt, &t.UpdatedAt,
		&t.RiderRatingGiven,
	)
	if err != nil {
		response.Success(c, nil)
		return
	}
	response.Success(c, t)
}

func (h *Handler) ListTrips(c *gin.Context) {
	driverID, _ := c.Get("userID")
	limit, offset := 20, 0
	if l := c.Query("limit"); l != "" {
		if v, err := strconv.Atoi(l); err == nil && v > 0 && v <= 100 {
			limit = v
		}
	}
	if o := c.Query("offset"); o != "" {
		if v, err := strconv.Atoi(o); err == nil && v >= 0 {
			offset = v
		}
	}
	rows, err := h.db.Query(context.Background(),
		`SELECT t.id, t.rider_id,
		        COALESCE(rp.full_name,''), COALESCE(rp.phone_number,''),
		        t.status::text, t.trip_type::text, t.note,
		        t.pickup_address, t.dropoff_address,
		        ST_Y(t.pickup_location::geometry), ST_X(t.pickup_location::geometry),
		        CASE WHEN t.dropoff_location IS NOT NULL THEN ST_Y(t.dropoff_location::geometry) END,
		        CASE WHEN t.dropoff_location IS NOT NULL THEN ST_X(t.dropoff_location::geometry) END,
		        t.offered_fare, t.fare,
		        COALESCE(pm.code,'cash'),
		        t.last_offer_by, t.offer_round,
		        t.driver_counter_count, t.rider_counter_count,
		        t.created_at::text, t.updated_at::text,
		        (SELECT r.score FROM ratings r
		         WHERE r.trip_id = t.id AND r.rater_role = 'driver' LIMIT 1)
		 FROM trips t
		 LEFT JOIN rider_profiles  rp ON rp.id = t.rider_id
		 LEFT JOIN payment_methods pm ON pm.id = t.payment_method_id
		 WHERE t.driver_id = $1
		   AND t.status IN ('completed','cancelled')
		 ORDER BY t.created_at DESC
		 LIMIT $2 OFFSET $3`,
		driverID, limit, offset,
	)
	if err != nil {
		slog.Error("ListTrips (driver) failed", "error", err)
		response.InternalError(c)
		return
	}
	defer rows.Close()
	trips := []DriverTripResponse{}
	for rows.Next() {
		var t DriverTripResponse
		if err := rows.Scan(
			&t.ID, &t.RiderID, &t.RiderName, &t.RiderPhone,
			&t.Status, &t.TripType, &t.Note,
			&t.PickupAddress, &t.DropoffAddress,
			&t.PickupLat, &t.PickupLng,
			&t.DropoffLat, &t.DropoffLng,
			&t.OfferedFare, &t.Fare, &t.PaymentMethod,
			&t.LastOfferBy, &t.OfferRound,
			&t.DriverCounterCount, &t.RiderCounterCount,
			&t.CreatedAt, &t.UpdatedAt,
			&t.RiderRatingGiven,
		); err != nil {
			slog.Error("ListTrips (driver) scan failed", "error", err)
			response.InternalError(c)
			return
		}
		trips = append(trips, t)
	}
	response.Success(c, trips)
}

// ── Ratings ───────────────────────────────────────────────────────────────────

func (h *Handler) RateRider(c *gin.Context) {
	tripID := c.Param("trip_id")
	driverID, _ := c.Get("userID")

	var req RateRiderRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	var riderID string
	err := h.db.QueryRow(context.Background(),
		`SELECT rider_id FROM trips
		 WHERE id = $1 AND driver_id = $2 AND status = 'completed'`,
		tripID, driverID,
	).Scan(&riderID)
	if err != nil {
		response.BadRequest(c, "Trip not found, not completed, or not assigned to you")
		return
	}

	var comment *string
	if req.Comment != "" {
		comment = &req.Comment
	}

	_, err = h.db.Exec(context.Background(),
		`INSERT INTO ratings (trip_id, rater_id, ratee_id, rater_role, score, comment)
		 VALUES ($1, $2, $3, 'driver', $4, $5)`,
		tripID, driverID, riderID, req.Score, comment,
	)
	if err != nil {
		if strings.Contains(err.Error(), "unique") {
			response.BadRequest(c, "You have already rated this trip")
			return
		}
		slog.Error("RateRider failed", "error", err)
		response.InternalError(c)
		return
	}

	response.Success(c, gin.H{"message": "Rating submitted", "score": req.Score})
}

// ── Earnings ─────────────────────────────────────────────────────────────────

func (h *Handler) GetEarnings(c *gin.Context) {
	driverID, _ := c.Get("userID")
	var e EarningsSummary
	err := h.db.QueryRow(context.Background(),
		`SELECT
		    COALESCE(SUM(fare) FILTER (WHERE created_at >= CURRENT_DATE), 0),
		    COALESCE(COUNT(*)  FILTER (WHERE created_at >= CURRENT_DATE), 0),
		    COALESCE(SUM(fare) FILTER (WHERE created_at >= DATE_TRUNC('week', NOW())), 0),
		    COALESCE(COUNT(*)  FILTER (WHERE created_at >= DATE_TRUNC('week', NOW())), 0),
		    COALESCE(SUM(fare) FILTER (WHERE created_at >= DATE_TRUNC('month', NOW())), 0),
		    COALESCE(COUNT(*)  FILTER (WHERE created_at >= DATE_TRUNC('month', NOW())), 0),
		    COALESCE(SUM(fare), 0),
		    COUNT(*)
		 FROM trips
		 WHERE driver_id = $1 AND status = 'completed'`,
		driverID,
	).Scan(
		&e.TodayTotal, &e.TodayTrips,
		&e.WeekTotal, &e.WeekTrips,
		&e.MonthTotal, &e.MonthTrips,
		&e.AllTimeTotal, &e.AllTimeTrips,
	)
	if err != nil {
		slog.Error("GetEarnings failed", "error", err)
		response.InternalError(c)
		return
	}
	h.db.QueryRow(context.Background(),
		`SELECT avg_rating, rating_count FROM driver_profiles WHERE id = $1`, driverID,
	).Scan(&e.AvgRating, &e.RatingCount)
	response.Success(c, e)
}

// GetDailyEarnings handles GET /api/v1/driver/earnings/daily
// Returns exactly 7 rows (today and the 6 days before it).
// Days with no completed trips are filled with 0 via generate_series + LEFT JOIN,
// so the Android chart always has a full week of bars.
//
// Note: dates are in the database server timezone (UTC by default on Supabase).
// If drivers operate in WITA (UTC+8), day boundaries will be off by 8 hours.
// To fix, replace `t.created_at::date` with
// `(t.created_at AT TIME ZONE 'Asia/Makassar')::date` once confirmed.
func (h *Handler) GetDailyEarnings(c *gin.Context) {
	driverID, _ := c.Get("userID")

	rows, err := h.db.Query(context.Background(),
		`SELECT
		     gs.day::date                AS date,
		     COALESCE(SUM(t.fare), 0)   AS total,
		     COUNT(t.id)::int           AS trips
		 FROM generate_series(
		     CURRENT_DATE - INTERVAL '6 days',
		     CURRENT_DATE,
		     INTERVAL '1 day'
		 ) AS gs(day)
		 LEFT JOIN trips t
		        ON t.driver_id        = $1
		       AND t.status           = 'completed'
		       AND t.created_at::date = gs.day
		 GROUP BY gs.day
		 ORDER BY gs.day ASC`,
		driverID,
	)
	if err != nil {
		slog.Error("GetDailyEarnings failed", "error", err)
		response.InternalError(c)
		return
	}
	defer rows.Close()

	days := []DailyEarningResponse{}
	for rows.Next() {
		var d DailyEarningResponse
		if err := rows.Scan(&d.Date, &d.Total, &d.Trips); err != nil {
			slog.Error("GetDailyEarnings scan failed", "error", err)
			response.InternalError(c)
			return
		}
		days = append(days, d)
	}
	response.Success(c, days)
}

// ── helpers ───────────────────────────────────────────────────────────────────

func formatRupiah(amount float64) string { return fmt.Sprintf("%.0f", amount) }

var _ = http.StatusOK
