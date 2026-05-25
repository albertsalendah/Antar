-- Migration: 016_vehicle_type_required_and_negotiation
-- 1. app_settings table (max_negotiation_rounds)
-- 2. vehicle_type_id required on trips
-- 3. Negotiation tracking columns on trips
-- 4. Updated trigger/pg_cron functions with vehicle type filter

-- ── app_settings ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS app_settings (
    key        text        PRIMARY KEY,
    value      text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO app_settings (key, value) VALUES ('max_negotiation_rounds', '6')
ON CONFLICT (key) DO NOTHING;

ALTER TABLE app_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY "app_settings: public read" ON app_settings FOR SELECT USING (true);
CREATE POLICY "app_settings: service all" ON app_settings FOR ALL USING (true) WITH CHECK (true);

-- ── vehicle_type_id required ──────────────────────────────────────────────────
UPDATE trips
SET vehicle_type_id = (SELECT id FROM vehicle_types WHERE is_enabled = true ORDER BY id LIMIT 1)
WHERE vehicle_type_id IS NULL;

ALTER TABLE trips ALTER COLUMN vehicle_type_id SET NOT NULL;

-- ── Negotiation tracking columns ──────────────────────────────────────────────
ALTER TABLE trips
    ADD COLUMN IF NOT EXISTS offer_round          int  NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_offer_by        text CHECK (last_offer_by IN ('driver','rider')),
    ADD COLUMN IF NOT EXISTS driver_counter_count int  NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS rider_counter_count  int  NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_trips_last_offer_by
    ON trips(last_offer_by) WHERE status = 'offered';

-- ── Updated trigger: filter by vehicle type ───────────────────────────────────
CREATE OR REPLACE FUNCTION notify_nearest_driver_on_insert()
RETURNS TRIGGER AS $$
DECLARE
    v_driver_id     uuid;
    v_fcm_token     text;
    v_island_radius int;
BEGIN
    IF NEW.island_id IS NULL OR NEW.pickup_location IS NULL THEN RETURN NEW; END IF;
    SELECT search_radius_m INTO v_island_radius FROM islands WHERE id = NEW.island_id;
    SELECT dp.id, dp.fcm_token INTO v_driver_id, v_fcm_token
    FROM driver_profiles dp
    JOIN driver_vehicles dv ON dv.id = dp.active_vehicle_id
    WHERE dp.is_online = true AND dp.island_id = NEW.island_id
      AND dp.fcm_token IS NOT NULL AND dp.last_location IS NOT NULL
      AND dv.vehicle_type_id = NEW.vehicle_type_id
      AND ST_DWithin(dp.last_location, NEW.pickup_location, v_island_radius)
    ORDER BY ST_Distance(dp.last_location, NEW.pickup_location) ASC LIMIT 1;
    IF v_driver_id IS NOT NULL THEN
        INSERT INTO driver_notification_queue (trip_id, driver_id, fcm_token)
        VALUES (NEW.id, v_driver_id, v_fcm_token);
        NEW.notified_driver_id := v_driver_id;
        NEW.notified_at := now();
        NEW.notification_attempts := 1;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ── Updated pg_cron fallback: filter by vehicle type ─────────────────────────
CREATE OR REPLACE FUNCTION process_trip_notification_timeouts()
RETURNS void AS $$
DECLARE
    r               RECORD;
    v_driver_id     uuid;
    v_fcm_token     text;
    v_island_radius int;
BEGIN
    FOR r IN
        SELECT t.id, t.island_id, t.pickup_location,
               t.notification_attempts, t.vehicle_type_id
        FROM trips t
        WHERE t.status = 'requested'
          AND ((t.notified_at IS NOT NULL AND t.notified_at < now() - interval '3 minutes')
               OR (t.notified_at IS NULL AND t.created_at < now() - interval '1 minute'))
    LOOP
        IF r.notification_attempts >= 5 THEN
            UPDATE trips SET status = 'cancelled', updated_at = now() WHERE id = r.id;
            CONTINUE;
        END IF;
        SELECT search_radius_m INTO v_island_radius FROM islands WHERE id = r.island_id;
        SELECT dp.id, dp.fcm_token INTO v_driver_id, v_fcm_token
        FROM driver_profiles dp
        JOIN driver_vehicles dv ON dv.id = dp.active_vehicle_id
        WHERE dp.is_online = true AND dp.island_id = r.island_id
          AND dp.fcm_token IS NOT NULL AND dp.last_location IS NOT NULL
          AND dv.vehicle_type_id = r.vehicle_type_id
          AND ST_DWithin(dp.last_location, r.pickup_location, v_island_radius)
          AND dp.id NOT IN (SELECT dnq.driver_id FROM driver_notification_queue dnq WHERE dnq.trip_id = r.id)
        ORDER BY ST_Distance(dp.last_location, r.pickup_location) ASC LIMIT 1;
        IF v_driver_id IS NOT NULL THEN
            INSERT INTO driver_notification_queue (trip_id, driver_id, fcm_token)
            VALUES (r.id, v_driver_id, v_fcm_token);
            UPDATE trips SET notified_driver_id = v_driver_id, notified_at = now(),
                notification_attempts = notification_attempts + 1, updated_at = now()
            WHERE id = r.id;
        ELSE
            UPDATE trips SET status = 'cancelled', updated_at = now() WHERE id = r.id;
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql;
