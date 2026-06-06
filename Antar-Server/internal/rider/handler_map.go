package rider

import (
	"context"
	"log/slog"
	"strconv"

	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"

	"antar/pkg/response"
)

// NearbyDrivers handles GET /api/v1/rider/nearby-drivers?lat=X&lng=Y&vehicle_type_id=N
// SEC-7 fix: vehicle_type_id is now passed as a query parameter ($5), never
// interpolated via fmt.Sprintf. Two separate parameterised queries are used
// depending on whether the optional filter is present.
func (h *Handler) NearbyDrivers(c *gin.Context) {
	latStr := c.Query("lat")
	lngStr := c.Query("lng")
	if latStr == "" || lngStr == "" {
		response.BadRequest(c, "lat and lng query parameters are required")
		return
	}
	lat, err := strconv.ParseFloat(latStr, 64)
	if err != nil || lat < -90 || lat > 90 {
		response.BadRequest(c, "Invalid lat")
		return
	}
	lng, err := strconv.ParseFloat(lngStr, 64)
	if err != nil || lng < -180 || lng > 180 {
		response.BadRequest(c, "Invalid lng")
		return
	}

	var vehicleTypeID *int
	if vtStr := c.Query("vehicle_type_id"); vtStr != "" {
		if v, err := strconv.Atoi(vtStr); err == nil && v > 0 {
			vehicleTypeID = &v
		}
	}

	var islandID, searchRadius int
	err = h.db.QueryRow(context.Background(),
		`SELECT i.id, i.search_radius_m
		 FROM islands i
		 WHERE ST_Within(ST_SetSRID(ST_MakePoint($1,$2),4326)::geometry, i.boundary::geometry)
		 LIMIT 1`, lng, lat,
	).Scan(&islandID, &searchRadius)
	if err != nil {
		slog.Warn("NearbyDrivers: location outside island boundaries", "lat", lat, "lng", lng)
		response.Success(c, []NearbyDriver{})
		return
	}

	const baseQuery = `
		SELECT dp.id, dp.full_name,
		       COALESCE(vt.name,'Unknown') AS vehicle_type,
		       ST_Y(dp.last_location::geometry) AS driver_lat,
		       ST_X(dp.last_location::geometry) AS driver_lng,
		       ST_Distance(dp.last_location, ST_SetSRID(ST_MakePoint($1,$2),4326)) AS distance_m
		FROM driver_profiles dp
		LEFT JOIN driver_vehicles dv ON dv.id = dp.active_vehicle_id
		LEFT JOIN vehicle_types   vt ON vt.id = dv.vehicle_type_id
		WHERE dp.is_online     = true
		  AND dp.island_id     = $3
		  AND dp.last_location IS NOT NULL
		  AND ST_DWithin(dp.last_location, ST_SetSRID(ST_MakePoint($1,$2),4326), $4)`

	var rows pgx.Rows
	if vehicleTypeID != nil {
		rows, err = h.db.Query(context.Background(),
			baseQuery+` AND dv.vehicle_type_id = $5 ORDER BY distance_m ASC LIMIT 50`,
			lng, lat, islandID, searchRadius, *vehicleTypeID,
		)
	} else {
		rows, err = h.db.Query(context.Background(),
			baseQuery+` ORDER BY distance_m ASC LIMIT 50`,
			lng, lat, islandID, searchRadius,
		)
	}
	if err != nil {
		slog.Error("NearbyDrivers query failed", "error", err)
		response.InternalError(c)
		return
	}
	defer rows.Close()

	drivers := []NearbyDriver{}
	for rows.Next() {
		var d NearbyDriver
		if err := rows.Scan(&d.DriverID, &d.FullName, &d.VehicleType,
			&d.Latitude, &d.Longitude, &d.DistanceM); err != nil {
			slog.Error("NearbyDrivers scan failed", "error", err)
			response.InternalError(c)
			return
		}
		drivers = append(drivers, d)
	}
	response.Success(c, drivers)
}
