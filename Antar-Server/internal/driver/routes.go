package driver

import (
	"antar/config"
	"antar/internal/middleware"

	"github.com/gin-gonic/gin"
)

func RegisterRoutes(rg *gin.RouterGroup, h *Handler, cfg *config.Config) {
	loginLimiter := middleware.NewLoginRateLimiter()
	offerLimiter := middleware.NewOfferRateLimiter()

	// Public
	rg.POST("/register", h.Register)
	rg.POST("/login", loginLimiter, h.Login)
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

		// Lookup
		auth.GET("/vehicle-types", h.GetVehicleTypes)

		// Location
		auth.POST("/location", h.UpdateLocation)
		auth.POST("/offline", h.GoOffline)

		// Vehicles
		auth.POST("/vehicles", h.AddVehicle)
		auth.GET("/vehicles", h.ListVehicles)
		auth.PATCH("/vehicles/:vehicle_id", h.UpdateVehicle)
		auth.DELETE("/vehicles/:vehicle_id", h.DeleteVehicle)
		auth.POST("/vehicles/:vehicle_id/set-active", h.SetActiveVehicle)

		// Earnings
		auth.GET("/earnings/daily", h.GetDailyEarnings)
		auth.GET("/earnings", h.GetEarnings)

		// Trips — static segments BEFORE :trip_id param routes
		auth.GET("/trips/active", h.GetActiveTrip)
		auth.GET("/trips/incoming", h.IncomingTrips)
		auth.GET("/trips", h.ListTrips)
		auth.POST("/trips/:trip_id/offer", offerLimiter, h.OfferPrice)
		auth.POST("/trips/:trip_id/counter", h.CounterOffer)
		auth.POST("/trips/:trip_id/start", h.StartTrip)
		auth.POST("/trips/:trip_id/arrive", h.ArriveAtPickup)
		auth.POST("/trips/:trip_id/complete", h.CompleteTrip)
		auth.POST("/trips/:trip_id/cancel", h.CancelTrip)
		auth.POST("/trips/:trip_id/rate", h.RateRider)
	}
}
