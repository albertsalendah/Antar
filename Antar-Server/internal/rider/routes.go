package rider

import (
	"antar/config"
	"antar/internal/middleware"

	"github.com/gin-gonic/gin"
)

func RegisterRoutes(rg *gin.RouterGroup, h *Handler, cfg *config.Config) {
	// Public
	rg.POST("/register", h.Register)
	rg.POST("/login", h.Login)
	rg.POST("/refresh", h.RefreshToken)

	// Protected
	auth := rg.Group("/")
	auth.Use(middleware.Auth(cfg))
	{
		// Device
		auth.POST("/fcm-token", h.SaveFCMToken)

		// Profile
		auth.GET("/profile", h.GetProfile)
		auth.PATCH("/profile", h.UpdateProfile)
		auth.POST("/avatar", h.UploadAvatar)

		// Map — ?lat=X&lng=Y&vehicle_type_id=N (vehicle_type_id optional filter)
		auth.GET("/nearby-drivers", h.NearbyDrivers)

		// Ride request
		auth.POST("/request-ride", h.RequestRide)

		// Static segments BEFORE :trip_id param routes
		auth.GET("/trips/active", h.GetActiveTrip)
		auth.GET("/trips", h.ListTrips)
		auth.GET("/trips/:trip_id", h.GetTrip)

		// Offer negotiation
		auth.POST("/trips/:trip_id/accept", h.AcceptOffer)
		auth.POST("/trips/:trip_id/reject", h.RejectOffer)
		auth.POST("/trips/:trip_id/counter", h.CounterOffer)

		// Cancellation
		auth.POST("/trips/:trip_id/cancel", h.CancelTrip)

		// Rating
		auth.POST("/trips/:trip_id/rate", h.RateDriver)
	}
}
