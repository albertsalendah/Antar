package rider

import (
	"context"
	"log/slog"
	"time"

	"github.com/gin-gonic/gin"

	"antar/pkg/fcm"
	"antar/pkg/response"
)

// ── Shared helper ─────────────────────────────────────────────────────────────

// checkDriverAvailability re-validates the same criteria
// notify_nearest_driver_on_insert() uses: online, active vehicle matches the
// trip's vehicle type, and not already on another active trip. Returns ""
// when available, or a user-facing reason when not (distinct per failure type
// per the locked design decision).
func (h *Handler) checkDriverAvailability(ctx context.Context, driverID string, vehicleTypeID int) (string, error) {
	var isOnline bool
	var activeVehicleTypeID *int
	err := h.db.QueryRow(ctx,
		`SELECT dp.is_online, dv.vehicle_type_id
		 FROM driver_profiles dp
		 LEFT JOIN driver_vehicles dv ON dv.id = dp.active_vehicle_id
		 WHERE dp.id = $1`,
		driverID,
	).Scan(&isOnline, &activeVehicleTypeID)
	if err != nil {
		return "Driver not found", nil
	}
	if !isOnline {
		return "Driver is no longer online", nil
	}
	if activeVehicleTypeID == nil || *activeVehicleTypeID != vehicleTypeID {
		return "Driver's active vehicle no longer matches this trip", nil
	}

	var onActiveTrip bool
	err = h.db.QueryRow(ctx,
		`SELECT EXISTS(
		     SELECT 1 FROM trips
		     WHERE (driver_id = $1 OR offered_by = $1)
		       AND status IN ('offered','agreed','arrived','in_progress')
		 )`,
		driverID,
	).Scan(&onActiveTrip)
	if err != nil {
		return "", err
	}
	if onActiveTrip {
		return "Driver is currently on another trip", nil
	}
	return "", nil
}

// notifyCandidateDriver fires the same "new_trip" FCM payload the
// queue-based processor uses, so the driver app routes to IncomingTrips
// the same way it does for the legacy flow.
func (h *Handler) notifyCandidateDriver(driverID, tripID string) {
	var token string
	h.db.QueryRow(context.Background(),
		`SELECT COALESCE(fcm_token,'') FROM driver_profiles WHERE id = $1`, driverID,
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
		slog.Warn("FCM notify candidate driver failed", "driver_id", driverID, "trip_id", tripID, "error", err)
	}
}

// ── Handlers ──────────────────────────────────────────────────────────────────

// ApproveCandidate handles POST /api/v1/rider/trips/:trip_id/approve-candidate
// Validate-at-approval: re-checks availability since the candidate may have
// gone offline since being suggested. Race-guards against the cron job
// reassigning the candidate mid-request by requiring the driver_id the
// client believes is current.
func (h *Handler) ApproveCandidate(c *gin.Context) {
	tripID := c.Param("trip_id")
	riderID, _ := c.Get("userID")

	var req ApproveCandidateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	var candidateDriverID *string
	var vehicleTypeID int
	err := h.db.QueryRow(context.Background(),
		`SELECT candidate_driver_id, vehicle_type_id FROM trips
		 WHERE id = $1 AND rider_id = $2 AND status = 'requested'`,
		tripID, riderID,
	).Scan(&candidateDriverID, &vehicleTypeID)
	if err != nil {
		response.NotFound(c, "Trip not found or no longer accepting a candidate")
		return
	}
	if candidateDriverID == nil || *candidateDriverID != req.DriverID {
		response.BadRequest(c, "The suggested driver has changed — please refresh")
		return
	}

	reason, err := h.checkDriverAvailability(context.Background(), req.DriverID, vehicleTypeID)
	if err != nil {
		slog.Error("ApproveCandidate availability check failed", "error", err)
		response.InternalError(c)
		return
	}
	if reason != "" {
		response.BadRequest(c, reason)
		return
	}

	result, err := h.db.Exec(context.Background(),
		`UPDATE trips
		 SET candidate_approved    = true,
		     candidate_approved_at = $1,
		     updated_at            = $1
		 WHERE id = $2 AND rider_id = $3 AND status = 'requested'
		   AND candidate_driver_id = $4 AND candidate_approved = false`,
		time.Now(), tripID, riderID, req.DriverID,
	)
	if err != nil {
		slog.Error("ApproveCandidate update failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.BadRequest(c, "Could not approve — candidate may have changed, please refresh")
		return
	}

	go h.notifyCandidateDriver(req.DriverID, tripID)

	response.Success(c, gin.H{"message": "Driver approved — waiting for them to respond"})
}

// RejectCandidate handles POST /api/v1/rider/trips/:trip_id/reject-candidate
// Excludes the current candidate, then finds the next nearest eligible driver
// using the exact same criteria as notify_nearest_driver_on_insert() (online,
// island, vehicle type, ST_DWithin radius, exclusions, not on active trip).
// Does NOT increment notification_attempts — that counter is reserved for
// cron-driven driver non-response timeouts only (locked decision #3).
func (h *Handler) RejectCandidate(c *gin.Context) {
	tripID := c.Param("trip_id")
	riderID, _ := c.Get("userID")

	var candidateDriverID *string
	var islandID, vehicleTypeID int
	var pickupLat, pickupLng float64
	err := h.db.QueryRow(context.Background(),
		`SELECT candidate_driver_id, island_id, vehicle_type_id,
		        ST_Y(pickup_location::geometry), ST_X(pickup_location::geometry)
		 FROM trips
		 WHERE id = $1 AND rider_id = $2 AND status = 'requested'`,
		tripID, riderID,
	).Scan(&candidateDriverID, &islandID, &vehicleTypeID, &pickupLat, &pickupLng)
	if err != nil {
		response.NotFound(c, "Trip not found or no longer requested")
		return
	}
	if candidateDriverID == nil {
		response.BadRequest(c, "No candidate driver to reject")
		return
	}

	if _, err := h.db.Exec(context.Background(),
		`INSERT INTO trip_driver_exclusions (trip_id, driver_id)
		 VALUES ($1, $2) ON CONFLICT (trip_id, driver_id) DO NOTHING`,
		tripID, *candidateDriverID,
	); err != nil {
		slog.Error("RejectCandidate exclusion insert failed", "error", err)
		response.InternalError(c)
		return
	}

	var nextDriverID *string
	// Mirrors notify_nearest_driver_on_insert() exactly — keep both in sync
	// if the trigger's matching criteria ever changes.
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

	if _, err := h.db.Exec(context.Background(),
		`UPDATE trips
		 SET candidate_driver_id   = $1,
		     candidate_approved    = false,
		     candidate_approved_at = NULL,
		     updated_at            = $2
		 WHERE id = $3 AND rider_id = $4`,
		nextDriverID, time.Now(), tripID, riderID,
	); err != nil {
		slog.Error("RejectCandidate update failed", "error", err)
		response.InternalError(c)
		return
	}

	response.Success(c, gin.H{
		"candidate_driver_id": nextDriverID,
		"message":             "Candidate rejected",
	})
}

// GetRejectedDrivers handles GET /api/v1/rider/trips/:trip_id/rejected-drivers
// Returns the full exclusion list for "No Driver Found" recovery (decision
// #8 — shown only there). is_available re-checks the same online + vehicle
// type + not-on-active-trip criteria as ApproveCandidate.
func (h *Handler) GetRejectedDrivers(c *gin.Context) {
	tripID := c.Param("trip_id")
	riderID, _ := c.Get("userID")

	var vehicleTypeID int
	err := h.db.QueryRow(context.Background(),
		`SELECT vehicle_type_id FROM trips WHERE id = $1 AND rider_id = $2`,
		tripID, riderID,
	).Scan(&vehicleTypeID)
	if err != nil {
		response.NotFound(c, "Trip not found")
		return
	}

	rows, err := h.db.Query(context.Background(),
		`SELECT dp.id, dp.full_name, dp.avatar_url, COALESCE(vt.name,''),
		        dp.avg_rating, dp.rating_count,
		        COALESCE(
		            dp.is_online
		            AND dv.vehicle_type_id = $2
		            AND NOT EXISTS (
		                SELECT 1 FROM trips t2
		                WHERE (t2.driver_id = dp.id OR t2.offered_by = dp.id)
		                  AND t2.status IN ('offered','agreed','arrived','in_progress')
		            ),
		            false
		        ) AS is_available
		 FROM trip_driver_exclusions e
		 JOIN driver_profiles dp ON dp.id = e.driver_id
		 LEFT JOIN driver_vehicles dv ON dv.id = dp.active_vehicle_id
		 LEFT JOIN vehicle_types   vt ON vt.id = dv.vehicle_type_id
		 WHERE e.trip_id = $1
		 ORDER BY e.excluded_at ASC`,
		tripID, vehicleTypeID,
	)
	if err != nil {
		slog.Error("GetRejectedDrivers query failed", "error", err)
		response.InternalError(c)
		return
	}
	defer rows.Close()

	drivers := []RejectedDriverResponse{}
	for rows.Next() {
		var d RejectedDriverResponse
		if err := rows.Scan(
			&d.DriverID, &d.FullName, &d.AvatarURL, &d.VehicleType,
			&d.AvgRating, &d.RatingCount, &d.IsAvailable,
		); err != nil {
			slog.Error("GetRejectedDrivers scan failed", "error", err)
			response.InternalError(c)
			return
		}
		drivers = append(drivers, d)
	}
	response.Success(c, drivers)
}

// ── REPLACE ReselectDriver in Antar-Server/internal/rider/handler_candidate.go ──
//
// Change summary [edge case fix]:
//   - Previous: blocked if candidate_driver_id IS NOT NULL (any existing candidate).
//   - Fixed:    blocks only when candidate_driver_id IS NOT NULL AND candidate_approved = true.
//   - Reason:   after DeclineCandidate or WithdrawOffer, the server assigns the next
//               candidate with candidate_approved = false and does NOT notify them.
//               If the rider taps Stop → NoDriverFound → tries to reselect, there may
//               be a dangling unapproved candidate blocking the call with a false
//               "A candidate is already assigned" error.
//               An unapproved candidate was never notified, so silently excluding them
//               and proceeding causes no UX harm to the driver.

func (h *Handler) ReselectDriver(c *gin.Context) {
	tripID := c.Param("trip_id")
	riderID, _ := c.Get("userID")

	var req ReselectDriverRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}

	var vehicleTypeID int
	var candidateDriverID *string
	var candidateApproved bool
	err := h.db.QueryRow(context.Background(),
		`SELECT vehicle_type_id, candidate_driver_id, candidate_approved FROM trips
		 WHERE id = $1 AND rider_id = $2 AND status = 'requested'`,
		tripID, riderID,
	).Scan(&vehicleTypeID, &candidateDriverID, &candidateApproved)
	if err != nil {
		response.NotFound(c, "Trip not found or no longer requested")
		return
	}

	// Block only if there is an actively approved candidate (they're already
	// waiting for a driver response). An unapproved candidate was never notified
	// so we can silently exclude them and proceed.
	if candidateDriverID != nil && candidateApproved {
		response.BadRequest(c, "A candidate is already assigned to this trip")
		return
	}

	// Silently exclude a dangling unapproved candidate (assigned by server after
	// a decline/withdraw, never notified because rider chose Stop before approving).
	if candidateDriverID != nil && !candidateApproved {
		if _, err := h.db.Exec(context.Background(),
			`INSERT INTO trip_driver_exclusions (trip_id, driver_id)
			 VALUES ($1, $2) ON CONFLICT (trip_id, driver_id) DO NOTHING`,
			tripID, *candidateDriverID,
		); err != nil {
			slog.Error("ReselectDriver unapproved candidate exclusion failed", "error", err)
			response.InternalError(c)
			return
		}
	}

	var wasExcluded bool
	h.db.QueryRow(context.Background(),
		`SELECT EXISTS(SELECT 1 FROM trip_driver_exclusions WHERE trip_id = $1 AND driver_id = $2)`,
		tripID, req.DriverID,
	).Scan(&wasExcluded)
	if !wasExcluded {
		response.BadRequest(c, "Driver is not in this trip's rejected list")
		return
	}

	reason, err := h.checkDriverAvailability(context.Background(), req.DriverID, vehicleTypeID)
	if err != nil {
		slog.Error("ReselectDriver availability check failed", "error", err)
		response.InternalError(c)
		return
	}
	if reason != "" {
		response.BadRequest(c, reason)
		return
	}

	if _, err := h.db.Exec(context.Background(),
		`DELETE FROM trip_driver_exclusions WHERE trip_id = $1 AND driver_id = $2`,
		tripID, req.DriverID,
	); err != nil {
		slog.Error("ReselectDriver exclusion delete failed", "error", err)
		response.InternalError(c)
		return
	}

	// WHERE clause accepts both NULL and unapproved-false states — covers the
	// case where we just cleared a dangling unapproved candidate above.
	result, err := h.db.Exec(context.Background(),
		`UPDATE trips
		 SET candidate_driver_id   = $1,
		     candidate_approved    = true,
		     candidate_approved_at = $2,
		     updated_at            = $2
		 WHERE id = $3 AND rider_id = $4
		   AND (candidate_driver_id IS NULL OR candidate_approved = false)`,
		req.DriverID, time.Now(), tripID, riderID,
	)
	if err != nil {
		slog.Error("ReselectDriver update failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.BadRequest(c, "Could not assign driver — trip state changed, please refresh")
		return
	}

	go h.notifyCandidateDriver(req.DriverID, tripID)

	response.Success(c, gin.H{"message": "Driver assigned — waiting for them to respond"})
}
