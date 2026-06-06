package driver

import (
	"context"
	"log/slog"

	"github.com/gin-gonic/gin"

	"antar/pkg/response"
)

func (h *Handler) GetEarnings(c *gin.Context) {
	driverID, _ := c.Get("userID")
	var e EarningsSummary
	err := h.db.QueryRow(context.Background(),
		`SELECT
		    COALESCE(SUM(fare) FILTER (WHERE created_at >= CURRENT_DATE), 0),
		    COALESCE(COUNT(*)  FILTER (WHERE created_at >= CURRENT_DATE), 0),
		    COALESCE(SUM(fare) FILTER (WHERE created_at >= DATE_TRUNC('week', NOW())), 0),
		    COALESCE(COUNT(*)  FILTER (WHERE created_at >= DATE_TRUNC('week', NOW())), 0),
		    COALESCE(SUM(fare) FILTER (WHERE created_at >= DATE_TRUNC('month', NOW())), 0),
		    COALESCE(COUNT(*)  FILTER (WHERE created_at >= DATE_TRUNC('month', NOW())), 0),
		    COALESCE(SUM(fare), 0),
		    COUNT(*)
		 FROM trips
		 WHERE driver_id = $1 AND status = 'completed'`,
		driverID,
	).Scan(
		&e.TodayTotal, &e.TodayTrips,
		&e.WeekTotal, &e.WeekTrips,
		&e.MonthTotal, &e.MonthTrips,
		&e.AllTimeTotal, &e.AllTimeTrips,
	)
	if err != nil {
		slog.Error("GetEarnings failed", "error", err)
		response.InternalError(c)
		return
	}
	h.db.QueryRow(context.Background(),
		`SELECT avg_rating, rating_count FROM driver_profiles WHERE id = $1`, driverID,
	).Scan(&e.AvgRating, &e.RatingCount)
	response.Success(c, e)
}

// GetDailyEarnings handles GET /api/v1/driver/earnings/daily
// EARN-TZ fix: generate_series bounds and trip date comparison now use
// Asia/Makassar (WITA, UTC+8) instead of the database server's UTC timezone.
// Trips completed after 16:00 UTC (midnight WITA) were previously appearing
// on the wrong day in the bar chart.
func (h *Handler) GetDailyEarnings(c *gin.Context) {
	driverID, _ := c.Get("userID")

	rows, err := h.db.Query(context.Background(),
		`SELECT
		     gs.day::date                AS date,
		     COALESCE(SUM(t.fare), 0)   AS total,
		     COUNT(t.id)::int           AS trips
		 FROM generate_series(
		     (now() AT TIME ZONE 'Asia/Makassar')::date - INTERVAL '6 days',
		     (now() AT TIME ZONE 'Asia/Makassar')::date,
		     INTERVAL '1 day'
		 ) AS gs(day)
		 LEFT JOIN trips t
		        ON t.driver_id = $1
		       AND t.status    = 'completed'
		       AND (t.created_at AT TIME ZONE 'Asia/Makassar')::date = gs.day
		 GROUP BY gs.day
		 ORDER BY gs.day ASC`,
		driverID,
	)
	if err != nil {
		slog.Error("GetDailyEarnings failed", "error", err)
		response.InternalError(c)
		return
	}
	defer rows.Close()

	days := []DailyEarningResponse{}
	for rows.Next() {
		var d DailyEarningResponse
		if err := rows.Scan(&d.Date, &d.Total, &d.Trips); err != nil {
			slog.Error("GetDailyEarnings scan failed", "error", err)
			response.InternalError(c)
			return
		}
		days = append(days, d)
	}
	response.Success(c, days)
}
