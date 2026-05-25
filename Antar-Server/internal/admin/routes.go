package admin

import (
	"antar/config"
	"antar/internal/middleware"

	"github.com/gin-gonic/gin"
)

func RegisterRoutes(rg *gin.RouterGroup, h *Handler, cfg *config.Config) {
	auth := rg.Group("/")
	auth.Use(middleware.Auth(cfg))
	{
		// Vehicle types
		auth.GET("/vehicle-types", h.ListVehicleTypes)
		auth.POST("/vehicle-types", h.CreateVehicleType)
		auth.PATCH("/vehicle-types/:type_id", h.UpdateVehicleType)

		// Fare rules — floor price per vehicle type (applies to both transport and errand)
		auth.GET("/fare-rules", h.ListFareRules)
		auth.PATCH("/fare-rules/:type_id", h.UpdateFareRule)

		// Payment methods
		auth.GET("/payment-methods", h.ListPaymentMethods)
		auth.PATCH("/payment-methods/:method_id", h.TogglePaymentMethod)

		// Islands — search radius control
		auth.GET("/islands", h.ListIslands)
		auth.PATCH("/islands/:island_id", h.UpdateIsland)

		// App settings
		auth.GET("/settings/negotiation", h.GetNegotiationSettings)
		auth.PATCH("/settings/negotiation", h.UpdateNegotiationSettings)
	}
}
