package rider

import (
	"antar/pkg/fcm"
	"antar/pkg/supabase"

	"github.com/jackc/pgx/v5/pgxpool"
)

type Handler struct {
	db       *pgxpool.Pool
	supabase *supabase.Client
	fcm      *fcm.Client
}

func NewHandler(db *pgxpool.Pool, sb *supabase.Client, fcmClient *fcm.Client) *Handler {
	return &Handler{db: db, supabase: sb, fcm: fcmClient}
}
