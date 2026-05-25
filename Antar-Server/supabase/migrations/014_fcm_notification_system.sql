-- Migration: 014_fcm_notification_system
-- Phase 2.3 — Sequential FCM notifications, island search radius, pg_cron fallback

-- ── 1. Enable pg_cron ─────────────────────────────────────────────────────────
-- Enable via Supabase dashboard (Database → Extensions) if this fails.
CREATE EXTENSION IF NOT EXISTS pg_cron;

-- ── 2. Island search radius ───────────────────────────────────────────────────
-- Controls driver search distance per island. Admin-configurable via
-- PATCH /api/v1/admin/islands/:island_id
ALTER TABLE islands
    ADD COLUMN IF NOT EXISTS search_radius_m int NOT NULL DEFAULT 5000;

UPDATE islands SET search_radius_m = 3000 WHERE name = 'Karakelang';
UPDATE islands SET search_radius_m = 1500 WHERE name = 'Kabaruan';
UPDATE islands SET search_radius_m = 1000 WHERE name = 'Salibabu';
UPDATE islands SET search_radius_m = 500  WHERE name = 'Sara Besar';
UPDATE islands SET search_radius_m = 300  WHERE name = 'Sara Kecil';

-- ── 3. FCM tokens on profiles ─────────────────────────────────────────────────
ALTER TABLE driver_profiles ADD COLUMN IF NOT EXISTS fcm_token text;
ALTER TABLE rider_profiles  ADD COLUMN IF NOT EXISTS fcm_token text;

-- ── 4. Notification tracking on trips ────────────────────────────────────────
ALTER TABLE trips
    ADD COLUMN IF NOT EXISTS notified_driver_id    uuid        REFERENCES driver_profiles(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS notified_at           timestamptz,
    ADD COLUMN IF NOT EXISTS notification_attempts int         NOT NULL DEFAULT 0;

-- ── 5. driver_notification_queue ─────────────────────────────────────────────
-- pg_cron populates this; Go notification processor drains it via FCM.
CREATE TABLE IF NOT EXISTS driver_notification_queue (
    id         bigserial   PRIMARY KEY,
    trip_id    uuid        NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    driver_id  uuid        NOT NULL REFERENCES driver_profiles(id) ON DELETE CASCADE,
    fcm_token  text        NOT NULL,
    status     text        NOT NULL DEFAULT 'pending'
                           CHECK (status IN ('pending','sent','failed')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_notif_queue_status  ON driver_notification_queue(status);
CREATE INDEX IF NOT EXISTS idx_notif_queue_trip_id ON driver_notification_queue(trip_id);

-- ── 6. Trigger: immediate notification on new trip insert ─────────────────────
CREATE OR REPLACE FUNCTION notify_nearest_driver_on_insert()
RETURNS TRIGGER AS $$
DECLARE
    v_driver_id     uuid;
    v_fcm_token     text;
    v_island_radius int;
BEGIN
    IF NEW.island_id IS NULL OR NEW.pickup_location IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT search_radius_m INTO v_island_radius
    FROM islands WHERE id = NEW.island_id;

    SELECT dp.id, dp.fcm_token
    INTO v_driver_id, v_fcm_token
    FROM driver_profiles dp
    WHERE dp.is_online     = true
      AND dp.island_id     = NEW.island_id
      AND dp.fcm_token     IS NOT NULL
      AND dp.last_location IS NOT NULL
      AND ST_DWithin(dp.last_location, NEW.pickup_location, v_island_radius)
    ORDER BY ST_Distance(dp.last_location, NEW.pickup_location) ASC
    LIMIT 1;

    IF v_driver_id IS NOT NULL THEN
        INSERT INTO driver_notification_queue (trip_id, driver_id, fcm_token)
        VALUES (NEW.id, v_driver_id, v_fcm_token);

        NEW.notified_driver_id    := v_driver_id;
        NEW.notified_at           := now();
        NEW.notification_attempts := 1;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trip_notify_nearest_driver
    BEFORE INSERT ON trips
    FOR EACH ROW
    WHEN (NEW.status = 'requested')
    EXECUTE FUNCTION notify_nearest_driver_on_insert();

-- ── 7. pg_cron fallback function ─────────────────────────────────────────────
-- Handles timed-out notifications (3-minute timeout) and trips that had
-- no nearby drivers at insert time. Cancels after 5 failed attempts.
CREATE OR REPLACE FUNCTION process_trip_notification_timeouts()
RETURNS void AS $$
DECLARE
    r               RECORD;
    v_driver_id     uuid;
    v_fcm_token     text;
    v_island_radius int;
BEGIN
    FOR r IN
        SELECT t.id, t.island_id, t.pickup_location, t.notification_attempts
        FROM trips t
        WHERE t.status = 'requested'
          AND (
            (t.notified_at IS NOT NULL AND t.notified_at < now() - interval '3 minutes')
            OR
            (t.notified_at IS NULL AND t.created_at < now() - interval '1 minute')
          )
    LOOP
        IF r.notification_attempts >= 5 THEN
            UPDATE trips SET status = 'cancelled', updated_at = now()
            WHERE id = r.id;
            CONTINUE;
        END IF;

        SELECT search_radius_m INTO v_island_radius
        FROM islands WHERE id = r.island_id;

        SELECT dp.id, dp.fcm_token
        INTO v_driver_id, v_fcm_token
        FROM driver_profiles dp
        WHERE dp.is_online     = true
          AND dp.island_id     = r.island_id
          AND dp.fcm_token     IS NOT NULL
          AND dp.last_location IS NOT NULL
          AND ST_DWithin(dp.last_location, r.pickup_location, v_island_radius)
          AND dp.id NOT IN (
              SELECT dnq.driver_id FROM driver_notification_queue dnq
              WHERE dnq.trip_id = r.id
          )
        ORDER BY ST_Distance(dp.last_location, r.pickup_location) ASC
        LIMIT 1;

        IF v_driver_id IS NOT NULL THEN
            INSERT INTO driver_notification_queue (trip_id, driver_id, fcm_token)
            VALUES (r.id, v_driver_id, v_fcm_token);

            UPDATE trips
            SET notified_driver_id    = v_driver_id,
                notified_at           = now(),
                notification_attempts = notification_attempts + 1,
                updated_at            = now()
            WHERE id = r.id;
        ELSE
            UPDATE trips SET status = 'cancelled', updated_at = now()
            WHERE id = r.id;
        END IF;
    END LOOP;
END;
$$ LANGUAGE plpgsql;

-- ── 8. Schedule pg_cron job ───────────────────────────────────────────────────
SELECT cron.unschedule(jobid) FROM cron.job WHERE jobname = 'trip-notification-fallback';
SELECT cron.schedule('trip-notification-fallback', '* * * * *',
    'SELECT process_trip_notification_timeouts()');
