package driver

import (
	"context"
	"io"
	"log/slog"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"antar/pkg/response"
)

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
		&p.IslandID, &p.IslandName,
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
