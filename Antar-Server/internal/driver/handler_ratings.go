package driver

import (
	"context"
	"log/slog"
	"strings"

	"github.com/gin-gonic/gin"

	"antar/pkg/response"
)

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
