package driver

import (
	"context"
	"fmt"
	"log/slog"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"

	"antar/pkg/fcm"
	"antar/pkg/response"
)

// IncomingTrips handles GET /api/v1/driver/trips/incoming
// Candidate-review: only returns trips where this driver is the approved
// candidate. Island/vehicle-type filters kept as defense-in-depth even
// though candidate_driver_id already guarantees correctness.
// rider_avatar_url added for the TripCard avatar (Kotlin rendering pending).
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
		        rp.avatar_url,
		        t.trip_type::text, t.note,
		        t.pickup_address, t.dropoff_address,
		        ST_Distance(t.pickup_location, ST_SetSRID(ST_MakePoint($1,$2),4326)) AS distance_m,
		        COALESCE(fr.default_fare, 2000) AS default_fare,
		        COALESCE(vt.name,'') AS vehicle_type_name,
		        COALESCE(pm.code,'cash'),
		        t.created_at::text,
		        t.candidate_approved_at::text
		 FROM trips t
		 LEFT JOIN rider_profiles  rp ON rp.id = t.rider_id
		 LEFT JOIN vehicle_types   vt ON vt.id = t.vehicle_type_id
		 LEFT JOIN fare_rules      fr ON fr.vehicle_type_id = t.vehicle_type_id
		 LEFT JOIN payment_methods pm ON pm.id = t.payment_method_id
		 WHERE t.status              = 'requested'
		   AND t.island_id           = $3
		   AND t.vehicle_type_id     = $4
		   AND t.candidate_driver_id = $5
		   AND t.candidate_approved  = true
		 ORDER BY t.created_at ASC`,
		driverLng, driverLat, islandID, activeVehicleTypeID, driverID,
	)
	if err != nil {
		slog.Error("IncomingTrips query failed", "error", err)
		response.InternalError(c)
		return
	}
	defer rows.Close()

	trips := []IncomingTripResponse{}
	for rows.Next() {
		var t IncomingTripResponse
		if err := rows.Scan(
			&t.ID, &t.RiderID, &t.RiderName, &t.RiderAvatarURL,
			&t.TripType, &t.Note,
			&t.PickupAddress, &t.DropoffAddress,
			&t.DistanceM, &t.DefaultFare, &t.VehicleTypeName,
			&t.PaymentMethod, &t.CreatedAt, &t.CandidateApprovedAt,
		); err != nil {
			slog.Error("IncomingTrips scan failed", "error", err)
			response.InternalError(c)
			return
		}
		trips = append(trips, t)
	}
	response.Success(c, trips)
}

// DeclineCandidate handles POST /api/v1/driver/trips/:trip_id/decline-candidate
// Driver declines a trip they are the approved candidate for, before submitting
// any offer. Immediately excludes the driver, finds and assigns the next nearest
// eligible candidate (same SQL as WithdrawOffer / RejectCandidate — keep all four
// in sync), notifies the rider via FCM, and notifies the next candidate driver.
// Does NOT increment notification_attempts (reserved for cron-driven timeouts only).
func (h *Handler) DeclineCandidate(c *gin.Context) {
	tripID := c.Param("trip_id")
	driverID, _ := c.Get("userID")

	// Verify driver is the current approved candidate for a requested trip
	var islandID, vehicleTypeID int
	var pickupLat, pickupLng float64
	var riderID string
	err := h.db.QueryRow(context.Background(),
		`SELECT island_id, vehicle_type_id,
		        ST_Y(pickup_location::geometry), ST_X(pickup_location::geometry),
		        rider_id
		 FROM trips
		 WHERE id                  = $1
		   AND candidate_driver_id = $2
		   AND candidate_approved  = true
		   AND status              = 'requested'`,
		tripID, driverID,
	).Scan(&islandID, &vehicleTypeID, &pickupLat, &pickupLng, &riderID)
	if err != nil {
		response.BadRequest(c, "Trip not found, not in requested status, or you are not the approved candidate")
		return
	}

	// Exclude this driver so they won't be reassigned to the same trip
	if _, err := h.db.Exec(context.Background(),
		`INSERT INTO trip_driver_exclusions (trip_id, driver_id)
		 VALUES ($1, $2) ON CONFLICT (trip_id, driver_id) DO NOTHING`,
		tripID, driverID,
	); err != nil {
		slog.Error("DeclineCandidate exclusion insert failed", "error", err)
		response.InternalError(c)
		return
	}

	// Find next nearest eligible candidate — mirrors notify_nearest_driver_on_insert(),
	// RejectCandidate, and WithdrawOffer exactly. Keep all four in sync.
	var nextDriverID *string
	_ = h.db.QueryRow(context.Background(),
		`SELECT dp.id
		 FROM driver_profiles dp
		 JOIN driver_vehicles dv ON dv.id = dp.active_vehicle_id
		 JOIN islands i ON i.id = $3
		 WHERE dp.is_online       = true
		   AND dp.island_id       = $3
		   AND dp.last_location   IS NOT NULL
		   AND dv.vehicle_type_id = $4
		   AND ST_DWithin(dp.last_location, ST_SetSRID(ST_MakePoint($1,$2),4326), i.search_radius_m)
		   AND NOT EXISTS (
		       SELECT 1 FROM trip_driver_exclusions e
		       WHERE e.trip_id = $5 AND e.driver_id = dp.id
		   )
		   AND NOT EXISTS (
		       SELECT 1 FROM trips t2
		       WHERE (t2.driver_id = dp.id OR t2.offered_by = dp.id)
		         AND t2.status IN ('offered','agreed','arrived','in_progress')
		   )
		 ORDER BY ST_Distance(dp.last_location, ST_SetSRID(ST_MakePoint($1,$2),4326)) ASC
		 LIMIT 1`,
		pickupLng, pickupLat, islandID, vehicleTypeID, tripID,
	).Scan(&nextDriverID)

	// Reset candidate fields — candidate_approved_at NULL so any new candidate's
	// countdown starts fresh from their own approval time, not a stale timestamp.
	result, err := h.db.Exec(context.Background(),
		`UPDATE trips
		 SET candidate_driver_id   = $1,
		     candidate_approved    = false,
		     candidate_approved_at = NULL,
		     updated_at            = $2
		 WHERE id                  = $3
		   AND candidate_driver_id = $4
		   AND candidate_approved  = true
		   AND status              = 'requested'`,
		nextDriverID, time.Now(), tripID, driverID,
	)
	if err != nil {
		slog.Error("DeclineCandidate update failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.BadRequest(c, "Trip state changed — could not decline")
		return
	}

	// Notify rider (FCM for backgrounded app — Realtime subscription on the
	// trips row handles the in-app UI update via candidate_driver_id change).
	go func() {
		var riderToken string
		h.db.QueryRow(context.Background(),
			`SELECT COALESCE(fcm_token,'') FROM rider_profiles WHERE id = $1`, riderID,
		).Scan(&riderToken)
		if riderToken == "" {
			return
		}
		if err := h.fcm.Send(context.Background(), fcm.Message{
			Token: riderToken,
			Notification: &fcm.Notification{
				Title: "Driver Menolak",
				Body:  "Driver menolak permintaan Anda — mencari driver berikutnya",
			},
			Data: map[string]string{
				"type":    "candidate_declined",
				"trip_id": tripID,
			},
		}); err != nil {
			slog.Warn("FCM notify rider of candidate decline failed",
				"trip_id", tripID, "error", err)
		}
	}()

	// Notify next candidate driver if one was found
	if nextDriverID != nil {
		go func() {
			var token string
			h.db.QueryRow(context.Background(),
				`SELECT COALESCE(fcm_token,'') FROM driver_profiles WHERE id = $1`, *nextDriverID,
			).Scan(&token)
			if token == "" {
				return
			}
			if err := h.fcm.Send(context.Background(), fcm.Message{
				Token: token,
				Notification: &fcm.Notification{
					Title: "Ada Permintaan Baru!",
					Body:  "Ada penumpang yang mencari driver di dekat Anda",
				},
				Data: map[string]string{
					"type":    "new_trip",
					"trip_id": tripID,
				},
			}); err != nil {
				slog.Warn("FCM notify next candidate after decline failed", "error", err)
			}
		}()
	}

	response.Success(c, gin.H{"message": "Trip declined"})
}

func (h *Handler) OfferPrice(c *gin.Context) {
	tripID := c.Param("trip_id")
	driverID, _ := c.Get("userID")

	var req OfferPriceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}

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

	if req.OfferedFare < defaultFare {
		response.BadRequest(c, fmt.Sprintf(
			"Offered fare must be at least the minimum fare of Rp %.0f", defaultFare))
		return
	}

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

func (h *Handler) CounterOffer(c *gin.Context) {
	tripID := c.Param("trip_id")
	driverID, _ := c.Get("userID")

	var req CounterOfferRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}

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

	driverCounterMax := maxRounds / 2
	if driverCounterCount >= driverCounterMax {
		response.BadRequest(c, fmt.Sprintf(
			"You have used all %d counter-offer attempts — please accept or reject the rider's offer",
			driverCounterMax))
		return
	}

	if req.OfferedFare < defaultFare {
		response.BadRequest(c, fmt.Sprintf(
			"Counter-offer must be at least the minimum fare of Rp %.0f", defaultFare))
		return
	}

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

// WithdrawOffer handles POST /api/v1/driver/trips/:trip_id/withdraw-offer
// Driver-side equivalent of rider's RejectOffer. CancelTrip can't be reused
// here — it filters on driver_id, which is still NULL pre-acceptance;
// offered_by is the correct ownership column for status='offered'. This was
// also the latent reason "Tolak & Batalkan" on CounterDecisionScreen likely
// already no-ops in production today.
//
// Design note (flagging for confirmation): the original TODO text only
// specified resetting to 'requested' + clearing offer fields. Since the
// trip's candidate_driver_id would otherwise still point at the withdrawing
// driver, this handler also excludes them and searches for the next nearest
// candidate immediately — same matching SQL as rider's RejectCandidate —
// so the rider doesn't stall. Does NOT increment notification_attempts
// (reserved for cron-driven non-response only, same as RejectCandidate).
func (h *Handler) WithdrawOffer(c *gin.Context) {
	tripID := c.Param("trip_id")
	driverID, _ := c.Get("userID")

	var islandID, vehicleTypeID int
	var pickupLat, pickupLng float64
	err := h.db.QueryRow(context.Background(),
		`SELECT island_id, vehicle_type_id,
		        ST_Y(pickup_location::geometry), ST_X(pickup_location::geometry)
		 FROM trips
		 WHERE id = $1 AND offered_by = $2 AND status = 'offered'`,
		tripID, driverID,
	).Scan(&islandID, &vehicleTypeID, &pickupLat, &pickupLng)
	if err != nil {
		response.BadRequest(c, "Trip not found, not offered, or not yours")
		return
	}

	if _, err := h.db.Exec(context.Background(),
		`INSERT INTO trip_driver_exclusions (trip_id, driver_id)
		 VALUES ($1, $2) ON CONFLICT (trip_id, driver_id) DO NOTHING`,
		tripID, driverID,
	); err != nil {
		slog.Error("WithdrawOffer exclusion insert failed", "error", err)
		response.InternalError(c)
		return
	}

	var nextDriverID *string
	// Mirrors notify_nearest_driver_on_insert() / rider's RejectCandidate —
	// keep all three in sync if the matching criteria ever changes.
	_ = h.db.QueryRow(context.Background(),
		`SELECT dp.id
		 FROM driver_profiles dp
		 JOIN driver_vehicles dv ON dv.id = dp.active_vehicle_id
		 JOIN islands i ON i.id = $3
		 WHERE dp.is_online       = true
		   AND dp.island_id       = $3
		   AND dp.last_location   IS NOT NULL
		   AND dv.vehicle_type_id = $4
		   AND ST_DWithin(dp.last_location, ST_SetSRID(ST_MakePoint($1,$2),4326), i.search_radius_m)
		   AND NOT EXISTS (
		       SELECT 1 FROM trip_driver_exclusions e
		       WHERE e.trip_id = $5 AND e.driver_id = dp.id
		   )
		   AND NOT EXISTS (
		       SELECT 1 FROM trips t2
		       WHERE (t2.driver_id = dp.id OR t2.offered_by = dp.id)
		         AND t2.status IN ('offered','agreed','arrived','in_progress')
		   )
		 ORDER BY ST_Distance(dp.last_location, ST_SetSRID(ST_MakePoint($1,$2),4326)) ASC
		 LIMIT 1`,
		pickupLng, pickupLat, islandID, vehicleTypeID, tripID,
	).Scan(&nextDriverID) // no rows = no eligible driver left, nextDriverID stays nil — not an error

	result, err := h.db.Exec(context.Background(),
		`UPDATE trips
		 SET status                = 'requested',
		     offered_by            = NULL,
		     offered_fare          = NULL,
		     last_offer_by         = NULL,
		     offer_round           = 0,
		     driver_counter_count  = 0,
		     rider_counter_count   = 0,
		     candidate_driver_id   = $1,
		     candidate_approved    = false,
		     candidate_approved_at = NULL,
		     updated_at            = $2
		 WHERE id = $3 AND offered_by = $4 AND status = 'offered'`,
		nextDriverID, time.Now(), tripID, driverID,
	)
	if err != nil {
		slog.Error("WithdrawOffer update failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.BadRequest(c, "Trip state changed — could not withdraw")
		return
	}

	if nextDriverID != nil {
		go func() {
			var token string
			h.db.QueryRow(context.Background(),
				`SELECT COALESCE(fcm_token,'') FROM driver_profiles WHERE id = $1`, *nextDriverID,
			).Scan(&token)
			if token == "" {
				return
			}
			if err := h.fcm.Send(context.Background(), fcm.Message{
				Token: token,
				Notification: &fcm.Notification{
					Title: "Ada Permintaan Baru!",
					Body:  "Ada penumpang yang mencari driver di dekat Anda",
				},
				Data: map[string]string{
					"type":    "new_trip",
					"trip_id": tripID,
				},
			}); err != nil {
				slog.Warn("FCM notify next candidate after withdraw failed", "error", err)
			}
		}()
	}

	response.Success(c, gin.H{"message": "Offer withdrawn"})
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
		response.BadRequest(c, "Trip cannot be started — must be in 'arrived' status and assigned to you")
		return
	}
	response.Success(c, gin.H{"message": "Trip started"})
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
		   AND t.status IN ('offered','agreed','arrived','in_progress')
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
