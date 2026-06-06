package driver

import (
	"context"
	"log/slog"
	"time"

	"github.com/gin-gonic/gin"

	"antar/pkg/response"
)

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
