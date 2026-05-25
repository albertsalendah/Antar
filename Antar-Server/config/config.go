package config

import (
	"log/slog"
	"os"

	"github.com/joho/godotenv"
)

type Config struct {
	DBUser     string
	DBPassword string
	DBHost     string
	DBPort     string
	DBName     string

	SupabaseURL       string
	SupabaseAnonKey   string
	SupabaseJWTSecret string

	// GoogleCredentialsFile is the path to the Firebase service account JSON.
	// Set via GOOGLE_APPLICATION_CREDENTIALS in .env or environment.
	// Used by pkg/fcm to authenticate with Firebase Cloud Messaging.
	GoogleCredentialsFile string

	AppEnv  string
	AppPort string
}

func Load() *Config {
	if err := godotenv.Load(); err != nil {
		slog.Warn("No .env file found, reading from environment")
	}

	cfg := &Config{
		DBUser:                os.Getenv("DB_USER"),
		DBPassword:            os.Getenv("DB_PASSWORD"),
		DBHost:                os.Getenv("DB_HOST"),
		DBPort:                os.Getenv("DB_PORT"),
		DBName:                os.Getenv("DB_NAME"),
		SupabaseURL:           os.Getenv("SUPABASE_URL"),
		SupabaseAnonKey:       os.Getenv("SUPABASE_ANON_KEY"),
		SupabaseJWTSecret:     os.Getenv("SUPABASE_JWT_SECRET"),
		GoogleCredentialsFile: os.Getenv("GOOGLE_APPLICATION_CREDENTIALS"),
		AppEnv:                os.Getenv("APP_ENV"),
		AppPort:               os.Getenv("APP_PORT"),
	}

	if cfg.AppPort == "" {
		cfg.AppPort = "8000"
	}
	if cfg.AppEnv == "" {
		cfg.AppEnv = "development"
	}

	cfg.validate()
	return cfg
}

func (c *Config) validate() {
	required := map[string]string{
		"DB_USER":                        c.DBUser,
		"DB_PASSWORD":                    c.DBPassword,
		"DB_HOST":                        c.DBHost,
		"DB_PORT":                        c.DBPort,
		"DB_NAME":                        c.DBName,
		"SUPABASE_URL":                   c.SupabaseURL,
		"SUPABASE_ANON_KEY":              c.SupabaseAnonKey,
		"SUPABASE_JWT_SECRET":            c.SupabaseJWTSecret,
		"GOOGLE_APPLICATION_CREDENTIALS": c.GoogleCredentialsFile,
	}
	for key, val := range required {
		if val == "" {
			slog.Error("Missing required environment variable", "key", key)
			os.Exit(1)
		}
	}
}
