package main

import (
	"antar/config"
	"antar/internal/admin"
	"antar/internal/driver"
	"antar/internal/rider"
	"antar/pkg/database"
	"antar/pkg/fcm"
	"antar/pkg/notification"
	"antar/pkg/supabase"
	"context"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/gin-gonic/gin"
)

func main() {
	// 1. Load config
	cfg := config.Load()

	// 2. Set Gin mode
	if cfg.AppEnv == "production" {
		gin.SetMode(gin.ReleaseMode)
	}

	// 3. Root context — cancelled on SIGTERM/SIGINT for clean shutdown
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, os.Interrupt)
	defer stop()

	// 4. Connect to DB pool
	db := database.Connect(cfg)
	defer db.Close()

	// 5. Shared Supabase auth client
	sb := supabase.NewClient(cfg)

	// 6. FCM client — required for push notifications
	fcmClient, err := fcm.NewClient(ctx)
	if err != nil {
		slog.Error("Failed to initialise FCM client", "error", err)
		os.Exit(1)
	}

	// 7. Start notification processor in background
	// Reads driver_notification_queue every 15s and fires FCM pushes.
	// pg_cron populates the queue; this goroutine drains it.
	proc := notification.NewProcessor(db, fcmClient)
	go proc.Start(ctx)

	// 8. Build module handlers
	driverHandler := driver.NewHandler(db, sb, fcmClient)
	riderHandler := rider.NewHandler(db, sb, fcmClient)
	adminHandler := admin.NewHandler(db)

	// 9. Router
	router := gin.Default()
	router.SetTrustedProxies(nil)

	router.GET("/ping", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"message": "pong"})
	})

	// 10. Register module routes
	v1 := router.Group("/api/v1")
	driver.RegisterRoutes(v1.Group("/driver"), driverHandler, cfg)
	rider.RegisterRoutes(v1.Group("/rider"), riderHandler, cfg)
	admin.RegisterRoutes(v1.Group("/admin"), adminHandler, cfg)

	// 11. Start server
	router.Run(":" + cfg.AppPort)
}
