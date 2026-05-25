package response

import (
	"net/http"

	"github.com/gin-gonic/gin"
)

func Success(c *gin.Context, data any) {
	c.JSON(http.StatusOK, gin.H{"data": data})
}

func Created(c *gin.Context, data any) {
	c.JSON(http.StatusCreated, gin.H{"data": data})
}

func BadRequest(c *gin.Context, message string) {
	c.JSON(http.StatusBadRequest, gin.H{"error": message})
}

func Unauthorized(c *gin.Context, message string) {
	c.JSON(http.StatusUnauthorized, gin.H{"error": message})
}

func NotFound(c *gin.Context, message string) {
	c.JSON(http.StatusNotFound, gin.H{"error": message})
}

// InternalError sends a 500. Never expose the real error to clients — log it server-side.
func InternalError(c *gin.Context) {
	c.JSON(http.StatusInternalServerError, gin.H{"error": "An internal error occurred"})
}
