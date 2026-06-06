package rider

import (
	"context"
	"fmt"
	"log/slog"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"antar/pkg/fcm"
	"antar/pkg/response"
)

// ── Trip query shared constant ────────────────────────────────────────────────
//
// tripSelect is used by GetActiveTrip, ListTrips, and GetTrip.
// Column order must match scanTrip() exactly.
const tripSelect = `
	SELECT t.id, t.rider_id, t.driver_id, t.offered_by, t.status,
	       t.trip_type::text, t.note,
	       t.pickup_address, t.dropoff_address,
	       t.offered_fare, t.fare,
	       COALESCE(pm.code,'cash'),
	       t.island_id,
	       t.last_offer_by, t.offer_round,
	       t.driver_counter_count, t.rider_counter_count,
	       t.created_at::text, t.updated_at::text,
	       COALESCE(dp.full_name,'')    AS driver_name,
	       COALESCE(dp.phone_number,'') AS driver_phone,
	       COALESCE(ST_Y(dp.last_location::geometry), 0) AS driver_lat,
	       COALESCE(ST_X(dp.last_location::geometry), 0) AS driver_lng,
	       COALESCE(ST_Y(t.pickup_location::geometry),  0) AS pickup_lat,
	       COALESCE(ST_X(t.pickup_location::geometry),  0) AS pickup_lng,
	       COALESCE(ST_Y(t.dropoff_location::geometry), 0) AS dropoff_lat,
	       COALESCE(ST_X(t.dropoff_location::geometry), 0) AS dropoff_lng,
	       EXISTS(
	           SELECT 1 FROM ratings r
	           WHERE r.trip_id = t.id AND r.rater_role = 'rider'
	       ) AS rider_has_rated
	FROM trips t
	LEFT JOIN payment_methods pm ON pm.id = t.payment_method_id
	LEFT JOIN driver_profiles dp ON dp.id = t.driver_id`

// scanTrip scans a row into TripResponse. Column order must match tripSelect.
func scanTrip(row interface {
	Scan(dest ...any) error
}, t *TripResponse) error {
	return row.Scan(
		&t.ID, &t.RiderID, &t.DriverID, &t.OfferedBy, &t.Status,
		&t.TripType, &t.Note,
		&t.PickupAddress, &t.DropoffAddress,
		&t.OfferedFare, &t.Fare,
		&t.PaymentMethod,
		&t.IslandID,
		&t.LastOfferBy, &t.OfferRound,
		&t.DriverCounterCount, &t.RiderCounterCount,
		&t.CreatedAt, &t.UpdatedAt,
		&t.DriverName, &t.DriverPhone,
		&t.DriverLat, &t.DriverLng,
		&t.PickupLat, &t.PickupLng,
		&t.DropoffLat, &t.DropoffLng,
		&t.RiderHasRated,
	)
}

// ── Handlers ──────────────────────────────────────────────────────────────────

func (h *Handler) RequestRide(c *gin.Context) {
	riderID, _ := c.Get("userID")
	var req RequestRideRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	var typeEnabled bool
	h.db.QueryRow(context.Background(),
		`SELECT EXISTS(SELECT 1 FROM vehicle_types WHERE id=$1 AND is_enabled=true)`,
		req.VehicleTypeID,
	).Scan(&typeEnabled)
	if !typeEnabled {
		response.BadRequest(c, "Invalid or disabled vehicle type")
		return
	}

	if req.PickupLat < -90 || req.PickupLat > 90 ||
		req.PickupLng < -180 || req.PickupLng > 180 {
		response.BadRequest(c, "Invalid pickup coordinates")
		return
	}
	if req.TripType == "transport" {
		if req.DropoffLat == 0 || req.DropoffLng == 0 || req.DropoffAddress == "" {
			response.BadRequest(c, "Transport trips require dropoff_lat, dropoff_lng, and dropoff_address")
			return
		}
	}
	if req.TripType == "errand" && strings.TrimSpace(req.Note) == "" {
		response.BadRequest(c, "Errand trips require a note describing what the driver should do")
		return
	}

	paymentCode := "cash"
	if req.PaymentMethod != "" {
		paymentCode = req.PaymentMethod
	}
	var paymentMethodID int
	err := h.db.QueryRow(context.Background(),
		`SELECT id FROM payment_methods WHERE code = $1 AND is_enabled = true`, paymentCode,
	).Scan(&paymentMethodID)
	if err != nil {
		response.BadRequest(c, "Payment method '"+paymentCode+"' is not available")
		return
	}

	var islandID int
	err = h.db.QueryRow(context.Background(),
		`SELECT id FROM islands
		 WHERE ST_Within(ST_SetSRID(ST_MakePoint($1,$2),4326)::geometry, boundary::geometry)
		 LIMIT 1`, req.PickupLng, req.PickupLat,
	).Scan(&islandID)
	if err != nil {
		slog.Warn("RequestRide: pickup outside island boundaries",
			"lat", req.PickupLat, "lng", req.PickupLng)
		response.BadRequest(c, "Pickup location is not within a recognized service area")
		return
	}

	_, _ = h.db.Exec(context.Background(),
		`UPDATE rider_profiles SET island_id = $1, updated_at = $2 WHERE id = $3`,
		islandID, time.Now(), riderID,
	)

	// REQ-DUP-SRV: guard against duplicate trips when the client retries after
	// a network drop. If an active trip already exists return it — do not insert.
	var existingTripID string
	if scanErr := h.db.QueryRow(context.Background(),
		`SELECT id FROM trips
		 WHERE rider_id = $1::uuid
		   AND status IN ('requested','offered','agreed','arrived','in_progress')
		 LIMIT 1`,
		riderID,
	).Scan(&existingTripID); scanErr == nil {
		response.Success(c, gin.H{
			"trip_id":   existingTripID,
			"status":    "existing",
			"trip_type": req.TripType,
			"message":   "You already have an active trip",
		})
		return
	}

	var dropoffLng, dropoffLat *float64
	var dropoffAddress *string
	if req.TripType == "transport" || req.DropoffAddress != "" {
		dropoffLng = &req.DropoffLng
		dropoffLat = &req.DropoffLat
		dropoffAddress = &req.DropoffAddress
	}
	var note *string
	if req.Note != "" {
		note = &req.Note
	}

	var tripID string
	err = h.db.QueryRow(context.Background(),
		`INSERT INTO trips (
		     rider_id, status, trip_type, island_id,
		     vehicle_type_id,
		     pickup_location,
		     dropoff_location,
		     pickup_address, dropoff_address,
		     note, payment_method_id,
		     created_at, updated_at
		 ) VALUES (
		     $1, 'requested'::trip_status, $2::trip_type, $3,
		     $4,
		     ST_SetSRID(ST_MakePoint($5,$6),4326),
		     CASE WHEN $7::float8 IS NOT NULL
		          THEN ST_SetSRID(ST_MakePoint($7,$8),4326)
		          ELSE NULL END,
		     $9, $10,
		     $11, $12,
		     $13, $13
		 ) RETURNING id`,
		riderID, req.TripType, islandID,
		req.VehicleTypeID,
		req.PickupLng, req.PickupLat,
		dropoffLng, dropoffLat,
		req.PickupAddress, dropoffAddress,
		note, paymentMethodID,
		time.Now(),
	).Scan(&tripID)
	if err != nil {
		slog.Error("RequestRide insert failed", "error", err)
		response.InternalError(c)
		return
	}

	response.Created(c, gin.H{
		"trip_id":         tripID,
		"status":          "requested",
		"trip_type":       req.TripType,
		"vehicle_type_id": req.VehicleTypeID,
		"island_id":       islandID,
		"message":         "Ride requested — searching for the nearest driver",
	})
}

func (h *Handler) AcceptOffer(c *gin.Context) {
	tripID := c.Param("trip_id")
	riderID, _ := c.Get("userID")

	var confirmedDriverID string
	err := h.db.QueryRow(context.Background(),
		`UPDATE trips
		 SET status     = 'agreed',
		     driver_id  = offered_by,
		     fare       = offered_fare,
		     offered_by = NULL,
		     updated_at = $1
		 WHERE id = $2 AND rider_id = $3 AND status = 'offered'
		 RETURNING driver_id`,
		time.Now(), tripID, riderID,
	).Scan(&confirmedDriverID)
	if err != nil {
		response.BadRequest(c, "No pending offer found for this trip")
		return
	}

	go func() {
		var driverToken string
		h.db.QueryRow(context.Background(),
			`SELECT COALESCE(fcm_token,'') FROM driver_profiles WHERE id = $1`,
			confirmedDriverID,
		).Scan(&driverToken)
		if driverToken != "" {
			if err := h.fcm.Send(context.Background(), fcm.Message{
				Token: driverToken,
				Notification: &fcm.Notification{
					Title: "Penumpang Menerima Penawaran!",
					Body:  "Segera jemput penumpang Anda",
				},
				Data: map[string]string{
					"type":    "offer_accepted",
					"trip_id": tripID,
				},
			}); err != nil {
				slog.Warn("FCM notify driver of acceptance failed",
					"driver_id", confirmedDriverID, "error", err)
			}
		}
	}()

	response.Success(c, gin.H{"message": "Offer accepted — driver is on the way"})
}

func (h *Handler) RejectOffer(c *gin.Context) {
	tripID := c.Param("trip_id")
	riderID, _ := c.Get("userID")

	result, err := h.db.Exec(context.Background(),
		`UPDATE trips
		 SET status               = 'requested',
		     offered_by           = NULL,
		     offered_fare         = NULL,
		     last_offer_by        = NULL,
		     offer_round          = 0,
		     driver_counter_count = 0,
		     rider_counter_count  = 0,
		     updated_at           = $1
		 WHERE id = $2 AND rider_id = $3 AND status = 'offered'`,
		time.Now(), tripID, riderID,
	)
	if err != nil {
		slog.Error("RejectOffer failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.BadRequest(c, "No pending offer found for this trip")
		return
	}
	response.Success(c, gin.H{"message": "Offer rejected — waiting for another driver"})
}

func (h *Handler) CounterOffer(c *gin.Context) {
	tripID := c.Param("trip_id")
	riderID, _ := c.Get("userID")

	var req CounterOfferRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	var driverID string
	var defaultFare float64
	var riderCounterCount, maxRounds int
	err := h.db.QueryRow(context.Background(),
		`SELECT t.offered_by,
		        COALESCE(fr.default_fare, 0),
		        t.rider_counter_count,
		        (SELECT value::int FROM app_settings WHERE key = 'max_negotiation_rounds')
		 FROM trips t
		 LEFT JOIN fare_rules fr ON fr.vehicle_type_id = t.vehicle_type_id
		 WHERE t.id = $1
		   AND t.rider_id = $2
		   AND t.status = 'offered'
		   AND t.last_offer_by = 'driver'`,
		tripID, riderID,
	).Scan(&driverID, &defaultFare, &riderCounterCount, &maxRounds)
	if err != nil {
		response.BadRequest(c, "No driver offer found for this trip, or countering is not available")
		return
	}

	if maxRounds <= 1 {
		response.BadRequest(c, "Price negotiation is not enabled — please accept or reject the offer")
		return
	}

	riderCounterMax := (maxRounds + 1) / 2
	if riderCounterCount >= riderCounterMax {
		response.BadRequest(c, fmt.Sprintf(
			"You have used all %d counter-offer attempts — please accept or reject the driver's offer",
			riderCounterMax))
		return
	}

	if req.OfferedFare < defaultFare {
		response.BadRequest(c, fmt.Sprintf(
			"Counter-offer must be at least the minimum fare of Rp %.0f", defaultFare))
		return
	}

	result, err := h.db.Exec(context.Background(),
		`UPDATE trips
		 SET offered_fare        = $1,
		     last_offer_by       = 'rider',
		     rider_counter_count = rider_counter_count + 1,
		     offer_round         = offer_round + 1,
		     updated_at          = $2
		 WHERE id = $3
		   AND rider_id = $4
		   AND status = 'offered'
		   AND last_offer_by = 'driver'`,
		req.OfferedFare, time.Now(), tripID, riderID,
	)
	if err != nil {
		slog.Error("CounterOffer (rider) failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.BadRequest(c, "Counter-offer could not be applied — trip state may have changed")
		return
	}

	go func() {
		var driverToken string
		h.db.QueryRow(context.Background(),
			`SELECT COALESCE(fcm_token,'') FROM driver_profiles WHERE id = $1`, driverID,
		).Scan(&driverToken)
		if driverToken != "" {
			if err := h.fcm.Send(context.Background(), fcm.Message{
				Token: driverToken,
				Notification: &fcm.Notification{
					Title: "Penumpang Balik Menawar!",
					Body:  fmt.Sprintf("Penumpang menawarkan harga Rp %.0f", req.OfferedFare),
				},
				Data: map[string]string{
					"type":         "rider_counter",
					"trip_id":      tripID,
					"offered_fare": fmt.Sprintf("%.0f", req.OfferedFare),
				},
			}); err != nil {
				slog.Warn("FCM notify driver of rider counter failed", "error", err)
			}
		}
	}()

	response.Success(c, gin.H{
		"message":      "Counter-offer submitted — waiting for driver",
		"offered_fare": req.OfferedFare,
	})
}

func (h *Handler) GetActiveTrip(c *gin.Context) {
	riderID, _ := c.Get("userID")
	var t TripResponse
	err := scanTrip(
		h.db.QueryRow(context.Background(),
			tripSelect+`
			 WHERE t.rider_id = $1
			   AND t.status IN ('requested','offered','agreed','arrived','in_progress')
			 ORDER BY t.created_at DESC LIMIT 1`,
			riderID,
		), &t,
	)
	if err != nil {
		response.Success(c, nil)
		return
	}
	response.Success(c, t)
}

func (h *Handler) ListTrips(c *gin.Context) {
	riderID, _ := c.Get("userID")
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
		tripSelect+`
		 WHERE t.rider_id = $1
		 ORDER BY t.created_at DESC
		 LIMIT $2 OFFSET $3`,
		riderID, limit, offset,
	)
	if err != nil {
		slog.Error("ListTrips failed", "error", err)
		response.InternalError(c)
		return
	}
	defer rows.Close()
	trips := []TripResponse{}
	for rows.Next() {
		var t TripResponse
		if err := scanTrip(rows, &t); err != nil {
			slog.Error("ListTrips scan failed", "error", err)
			response.InternalError(c)
			return
		}
		trips = append(trips, t)
	}
	response.Success(c, trips)
}

func (h *Handler) GetTrip(c *gin.Context) {
	tripID := c.Param("trip_id")
	riderID, _ := c.Get("userID")
	var t TripResponse
	err := scanTrip(
		h.db.QueryRow(context.Background(),
			tripSelect+` WHERE t.id = $1 AND t.rider_id = $2`,
			tripID, riderID,
		), &t,
	)
	if err != nil {
		response.NotFound(c, "Trip not found")
		return
	}
	response.Success(c, t)
}

func (h *Handler) CancelTrip(c *gin.Context) {
	tripID := c.Param("trip_id")
	riderID, _ := c.Get("userID")
	result, err := h.db.Exec(context.Background(),
		`UPDATE trips SET status = 'cancelled', updated_at = $1
		 WHERE id = $2 AND rider_id = $3 AND status = 'requested'`,
		time.Now(), tripID, riderID,
	)
	if err != nil {
		slog.Error("CancelTrip failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.BadRequest(c, "Trip cannot be cancelled — it may already have an offer")
		return
	}
	response.Success(c, gin.H{"message": "Trip cancelled"})
}
