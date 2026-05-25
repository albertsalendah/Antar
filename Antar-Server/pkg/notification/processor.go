package notification

import (
	"antar/pkg/fcm"
	"context"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Processor reads pending rows from driver_notification_queue every 15 seconds
// and fires FCM pushes. It is the only component that touches FCM for
// new-trip notifications — pg_cron handles the queue population logic.
//
// Design notes:
//   - Stateless: unprocessed rows survive server restarts (status stays 'pending')
//   - Non-blocking: FCM failures are logged and marked 'failed', never retried
//   - Safe: checks trip status before sending to avoid pushing for cancelled trips
type Processor struct {
	db  *pgxpool.Pool
	fcm *fcm.Client
}

func NewProcessor(db *pgxpool.Pool, fcmClient *fcm.Client) *Processor {
	return &Processor{db: db, fcm: fcmClient}
}

// Start launches the background processing loop.
// Call this in a goroutine from main: go processor.Start(ctx)
// The loop exits cleanly when ctx is cancelled (e.g. on server shutdown).
func (p *Processor) Start(ctx context.Context) {
	slog.Info("Notification processor started")
	ticker := time.NewTicker(15 * time.Second)
	defer ticker.Stop()

	// Process any pending items immediately on start
	p.processQueue(ctx)

	for {
		select {
		case <-ticker.C:
			p.processQueue(ctx)
		case <-ctx.Done():
			slog.Info("Notification processor stopped")
			return
		}
	}
}

type queueEntry struct {
	id       int64
	tripID   string
	driverID string
	fcmToken string
}

func (p *Processor) processQueue(ctx context.Context) {
	// Only process items whose trip is still 'requested' —
	// if the trip was cancelled or already offered, skip the notification.
	rows, err := p.db.Query(ctx,
		`SELECT q.id, q.trip_id, q.driver_id, q.fcm_token
		 FROM driver_notification_queue q
		 JOIN trips t ON t.id = q.trip_id
		 WHERE q.status  = 'pending'
		   AND t.status  = 'requested'
		 ORDER BY q.created_at ASC
		 LIMIT 20`)
	if err != nil {
		slog.Error("notification processor: query failed", "error", err)
		return
	}
	defer rows.Close()

	var entries []queueEntry
	for rows.Next() {
		var e queueEntry
		if err := rows.Scan(&e.id, &e.tripID, &e.driverID, &e.fcmToken); err != nil {
			slog.Warn("notification processor: scan failed", "error", err)
			continue
		}
		entries = append(entries, e)
	}
	rows.Close()

	for _, e := range entries {
		err := p.fcm.Send(ctx, fcm.Message{
			Token: e.fcmToken,
			Notification: &fcm.Notification{
				Title: "Ada Permintaan Baru!",
				Body:  "Ada penumpang yang mencari driver di dekat Anda",
			},
			Data: map[string]string{
				"type":    "new_trip",
				"trip_id": e.tripID,
			},
		})

		status := "sent"
		if err != nil {
			slog.Warn("notification processor: FCM send failed",
				"queue_id", e.id,
				"driver_id", e.driverID,
				"error", err)
			status = "failed"
		}

		if _, dbErr := p.db.Exec(ctx,
			`UPDATE driver_notification_queue SET status = $1 WHERE id = $2`,
			status, e.id,
		); dbErr != nil {
			slog.Warn("notification processor: status update failed",
				"queue_id", e.id, "error", dbErr)
		}
	}
}
