package driver

import (
	"context"
	"log/slog"
	"time"

	"github.com/gin-gonic/gin"

	"antar/pkg/response"
)

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
