package driver

import (
	"context"
	"log/slog"
	"time"

	"github.com/gin-gonic/gin"

	"antar/pkg/response"
)

func (h *Handler) GetVehicleTypes(c *gin.Context) {
	rows, err := h.db.Query(context.Background(),
		`SELECT id, name, code, description FROM vehicle_types
		 WHERE is_enabled = true ORDER BY id DESC`)
	if err != nil {
		slog.Error("GetVehicleTypes failed", "error", err)
		response.InternalError(c)
		return
	}
	defer rows.Close()
	types := []VehicleType{}
	for rows.Next() {
		var vt VehicleType
		if err := rows.Scan(&vt.ID, &vt.Name, &vt.Code, &vt.Description); err != nil {
			response.InternalError(c)
			return
		}
		types = append(types, vt)
	}
	response.Success(c, types)
}

func (h *Handler) AddVehicle(c *gin.Context) {
	var req AddVehicleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	driverID, _ := c.Get("userID")
	var typeExists bool
	h.db.QueryRow(context.Background(),
		`SELECT EXISTS(SELECT 1 FROM vehicle_types WHERE id=$1 AND is_enabled=true)`,
		req.VehicleTypeID,
	).Scan(&typeExists)
	if !typeExists {
		response.BadRequest(c, "Invalid or disabled vehicle type")
		return
	}
	var vehicleID string
	err := h.db.QueryRow(context.Background(),
		`INSERT INTO driver_vehicles
		   (driver_id,vehicle_type_id,license_plate,make,model,year,color,created_at,updated_at)
		 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$8) RETURNING id`,
		driverID, req.VehicleTypeID, req.LicensePlate,
		req.Make, req.Model, req.Year, req.Color, time.Now(),
	).Scan(&vehicleID)
	if err != nil {
		slog.Error("AddVehicle failed", "error", err)
		response.InternalError(c)
		return
	}
	response.Created(c, gin.H{"message": "Vehicle added successfully", "vehicle_id": vehicleID})
}

func (h *Handler) ListVehicles(c *gin.Context) {
	driverID, _ := c.Get("userID")
	rows, err := h.db.Query(context.Background(),
		`SELECT dv.id,dv.vehicle_type_id,vt.name,
		        dv.license_plate,dv.make,dv.model,dv.year,dv.color,dv.is_active
		 FROM driver_vehicles dv
		 JOIN vehicle_types vt ON vt.id=dv.vehicle_type_id
		 WHERE dv.driver_id=$1 ORDER BY dv.created_at ASC`,
		driverID,
	)
	if err != nil {
		slog.Error("ListVehicles failed", "error", err)
		response.InternalError(c)
		return
	}
	defer rows.Close()
	vehicles := []VehicleResponse{}
	for rows.Next() {
		var v VehicleResponse
		if err := rows.Scan(&v.ID, &v.VehicleTypeID, &v.VehicleType,
			&v.LicensePlate, &v.Make, &v.Model, &v.Year, &v.Color, &v.IsActive,
		); err != nil {
			response.InternalError(c)
			return
		}
		vehicles = append(vehicles, v)
	}
	response.Success(c, vehicles)
}

func (h *Handler) UpdateVehicle(c *gin.Context) {
	vehicleID := c.Param("vehicle_id")
	driverID, _ := c.Get("userID")
	var req UpdateVehicleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		response.BadRequest(c, err.Error())
		return
	}
	result, err := h.db.Exec(context.Background(),
		`UPDATE driver_vehicles
		 SET make=COALESCE(NULLIF($1,''),make),model=COALESCE(NULLIF($2,''),model),
		     year=CASE WHEN $3>0 THEN $3 ELSE year END,
		     color=COALESCE(NULLIF($4,''),color),is_active=COALESCE($5,is_active),
		     updated_at=$6
		 WHERE id=$7 AND driver_id=$8`,
		req.Make, req.Model, req.Year, req.Color, req.IsActive,
		time.Now(), vehicleID, driverID,
	)
	if err != nil {
		slog.Error("UpdateVehicle failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.NotFound(c, "Vehicle not found")
		return
	}
	response.Success(c, gin.H{"message": "Vehicle updated successfully"})
}

func (h *Handler) DeleteVehicle(c *gin.Context) {
	vehicleID := c.Param("vehicle_id")
	driverID, _ := c.Get("userID")
	result, err := h.db.Exec(context.Background(),
		`DELETE FROM driver_vehicles WHERE id=$1 AND driver_id=$2`, vehicleID, driverID,
	)
	if err != nil {
		slog.Error("DeleteVehicle failed", "error", err)
		response.InternalError(c)
		return
	}
	if result.RowsAffected() == 0 {
		response.NotFound(c, "Vehicle not found")
		return
	}
	response.Success(c, gin.H{"message": "Vehicle deleted successfully"})
}

func (h *Handler) SetActiveVehicle(c *gin.Context) {
	vehicleID := c.Param("vehicle_id")
	driverID, _ := c.Get("userID")
	var ok bool
	h.db.QueryRow(context.Background(),
		`SELECT EXISTS(SELECT 1 FROM driver_vehicles
		  WHERE id=$1 AND driver_id=$2 AND is_active=true)`,
		vehicleID, driverID,
	).Scan(&ok)
	if !ok {
		response.BadRequest(c, "Vehicle not found or is currently disabled")
		return
	}
	_, err := h.db.Exec(context.Background(),
		`UPDATE driver_profiles SET active_vehicle_id=$1 WHERE id=$2`, vehicleID, driverID,
	)
	if err != nil {
		slog.Error("SetActiveVehicle failed", "error", err)
		response.InternalError(c)
		return
	}
	response.Success(c, gin.H{"message": "Active vehicle updated"})
}
