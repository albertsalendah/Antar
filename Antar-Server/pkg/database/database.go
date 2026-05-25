package database

import (
	"antar/config"
	"context"
	"fmt"
	"log/slog"
	"net/url"
	"os"

	"github.com/jackc/pgx/v5/pgxpool"
)

func Connect(cfg *config.Config) *pgxpool.Pool {
	connString := fmt.Sprintf("postgres://%s:%s@%s:%s/%s",
		cfg.DBUser,
		url.PathEscape(cfg.DBPassword),
		cfg.DBHost,
		cfg.DBPort,
		cfg.DBName,
	)

	pool, err := pgxpool.New(context.Background(), connString)
	if err != nil {
		slog.Error("Unable to create DB connection pool", "error", err)
		os.Exit(1)
	}

	if err := pool.Ping(context.Background()); err != nil {
		slog.Error("Unable to reach database", "error", err)
		os.Exit(1)
	}

	slog.Info("Database connection pool established")
	return pool
}
