package middleware

import (
	"net/http"

	"github.com/gin-gonic/gin"
	limiter "github.com/ulule/limiter/v3"
	mgin "github.com/ulule/limiter/v3/drivers/middleware/gin"
	"github.com/ulule/limiter/v3/drivers/store/memory"
)

// NewLoginRateLimiter returns a Gin middleware that allows 5 login attempts
// per IP per minute. Applied to POST /driver/login and POST /rider/login.
// Protects against brute-force credential attacks.
func NewLoginRateLimiter() gin.HandlerFunc {
	rate := limiter.Rate{
		Period: 60,           // 1 minute
		Limit:  5,
	}
	store := memory.NewStore()
	instance := limiter.New(store, rate)
	return mgin.NewMiddleware(instance)
}

// NewOfferRateLimiter returns a Gin middleware that allows 10 offer submissions
// per IP per minute. Applied to POST /driver/trips/:id/offer.
// Prevents a driver from hammering the offer endpoint on slow connections.
func NewOfferRateLimiter() gin.HandlerFunc {
	rate := limiter.Rate{
		Period: 60,
		Limit:  10,
	}
	store := memory.NewStore()
	instance := limiter.New(store, rate)
	return mgin.NewMiddleware(instance)
}

// RateLimitExceeded is a custom 429 handler that returns a consistent JSON
// error body matching the rest of the API ({"error": "..."}).
// Wire it via router.Use() or pass as mgin option if needed.
func RateLimitExceeded(c *gin.Context) {
	c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{
		"error": "Too many requests — please slow down and try again shortly",
	})
}
