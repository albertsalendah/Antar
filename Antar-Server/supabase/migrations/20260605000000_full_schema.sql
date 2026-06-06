-- Migration: 20260605000000_full_schema
-- Complete schema for Antar — Kepulauan Talaud ride-hailing platform
-- Replaces all previous migrations. Apply to a fresh Supabase project.
-- Island boundary geometries are seeded separately (they are static reference data).
-- Test data (trips, ratings, notifications, user profiles) is excluded.

-- ── Extensions ────────────────────────────────────────────────────────────────
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pg_cron;

-- ── Enums ─────────────────────────────────────────────────────────────────────
DO $$ BEGIN
    CREATE TYPE trip_status AS ENUM (
        'requested',
        'offered',
        'agreed',
        'arrived',
        'in_progress',
        'completed',
        'cancelled'
    );
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    CREATE TYPE trip_type AS ENUM ('transport', 'errand');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ── islands ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS islands (
    id             serial      PRIMARY KEY,
    name           text        NOT NULL UNIQUE,
    boundary       geography(Polygon, 4326),
    search_radius_m int        NOT NULL DEFAULT 5000,
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_islands_boundary ON islands USING GIST(boundary);

ALTER TABLE islands ENABLE ROW LEVEL SECURITY;

CREATE POLICY "islands: public read"
    ON islands FOR SELECT USING (true);

CREATE POLICY "islands: service all"
    ON islands FOR ALL TO service_role
    USING (true) WITH CHECK (true);

-- Seed island names and search radii (boundaries loaded separately via GeoJSON)
INSERT INTO islands (name, search_radius_m) VALUES
    ('Kabaruan',   1500),
    ('Karakelang', 3000),
    ('Salibabu',   1000),
    ('Sara Besar',  500),
    ('Sara Kecil',  300)
ON CONFLICT (name) DO UPDATE SET search_radius_m = EXCLUDED.search_radius_m;

-- ── vehicle_types ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS vehicle_types (
    id          serial      PRIMARY KEY,
    name        text        NOT NULL UNIQUE,
    code        text        NOT NULL UNIQUE,
    description text,
    is_enabled  boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE vehicle_types ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Anyone can view enabled vehicle types"
    ON vehicle_types FOR SELECT
    USING (is_enabled = true);

-- Seed vehicle types
INSERT INTO vehicle_types (name, code, description, is_enabled) VALUES
    ('Car',       'car',       'Standard 4-wheeled passenger car',                      false),
    ('Motorbike', 'motorbike', 'Standard 2-wheeled motorcycle',                         true),
    ('Bentor',    'bentor',    'Motorized tricycle with sidecar, common in Manado area', true)
ON CONFLICT (code) DO NOTHING;

-- ── driver_profiles ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS driver_profiles (
    id               uuid        PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    full_name        text        NOT NULL DEFAULT '',
    phone_number     text        NOT NULL DEFAULT '',
    email            text,
    avatar_url       text,
    is_online        boolean     NOT NULL DEFAULT false,
    last_location    geography(Point, 4326),
    last_lat         double precision,
    last_lng         double precision,
    active_vehicle_id uuid,      -- FK added after driver_vehicles is created
    island_id        int         REFERENCES islands(id) ON DELETE SET NULL,
    fcm_token        text,
    avg_rating       numeric(3,2),
    rating_count     int         NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

COMMENT ON COLUMN driver_profiles.active_vehicle_id IS
    'The single vehicle the driver is currently operating. Only one vehicle can be active at a time. Set via POST /api/v1/driver/vehicles/:id/set-active.';

CREATE INDEX IF NOT EXISTS idx_driver_last_location
    ON driver_profiles USING GIST(last_location);

CREATE INDEX IF NOT EXISTS idx_driver_profiles_online_island
    ON driver_profiles(island_id) WHERE is_online = true;

ALTER TABLE driver_profiles ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Driver can view own profile"
    ON driver_profiles FOR SELECT TO authenticated
    USING (auth.uid() = id);

CREATE POLICY "Driver can update own profile"
    ON driver_profiles FOR UPDATE TO authenticated
    USING (auth.uid() = id);

CREATE POLICY "Server can insert driver profile"
    ON driver_profiles FOR INSERT TO service_role
    WITH CHECK (true);

CREATE POLICY "riders can read driver location"
    ON driver_profiles FOR SELECT TO authenticated
    USING (true);

-- ── driver_vehicles ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS driver_vehicles (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    driver_id       uuid        NOT NULL REFERENCES driver_profiles(id) ON DELETE CASCADE,
    vehicle_type_id int         NOT NULL REFERENCES vehicle_types(id),
    license_plate   text        NOT NULL,
    make            text,
    model           text,
    year            smallint,
    color           text,
    is_active       boolean     NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (driver_id, license_plate)
);

COMMENT ON COLUMN driver_vehicles.is_active IS
    'Whether this vehicle is enabled/available. NOT the same as being the active vehicle for today — that is tracked by driver_profiles.active_vehicle_id.';

CREATE INDEX IF NOT EXISTS idx_driver_vehicles_driver_id
    ON driver_vehicles(driver_id);

ALTER TABLE driver_vehicles ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Driver can view own vehicles"
    ON driver_vehicles FOR SELECT TO authenticated
    USING (auth.uid() = driver_id);

CREATE POLICY "Driver can insert own vehicles"
    ON driver_vehicles FOR INSERT TO authenticated
    WITH CHECK (auth.uid() = driver_id);

CREATE POLICY "Driver can update own vehicles"
    ON driver_vehicles FOR UPDATE TO authenticated
    USING (auth.uid() = driver_id);

CREATE POLICY "Driver can delete own vehicles"
    ON driver_vehicles FOR DELETE TO authenticated
    USING (auth.uid() = driver_id);

-- Now add the FK from driver_profiles → driver_vehicles
ALTER TABLE driver_profiles
    ADD CONSTRAINT driver_profiles_active_vehicle_id_fkey
    FOREIGN KEY (active_vehicle_id) REFERENCES driver_vehicles(id) ON DELETE SET NULL;

-- ── rider_profiles ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS rider_profiles (
    id           uuid        PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    full_name    text        NOT NULL DEFAULT '',
    phone_number text        NOT NULL DEFAULT '',
    email        text,
    avatar_url   text,
    island_id    int         REFERENCES islands(id) ON DELETE SET NULL,
    fcm_token    text,
    avg_rating   numeric(3,2),
    rating_count int         NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE rider_profiles ENABLE ROW LEVEL SECURITY;

CREATE POLICY "rider_profiles: read"
    ON rider_profiles FOR SELECT TO authenticated
    USING (auth.uid() = id);

CREATE POLICY "rider_profiles: update"
    ON rider_profiles FOR UPDATE TO authenticated
    USING (auth.uid() = id);

CREATE POLICY "rider_profiles: service insert"
    ON rider_profiles FOR INSERT TO service_role
    WITH CHECK (true);

-- ── payment_methods ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS payment_methods (
    id         serial      PRIMARY KEY,
    name       text        NOT NULL,
    code       text        NOT NULL UNIQUE,
    is_enabled boolean     NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE payment_methods ENABLE ROW LEVEL SECURITY;

CREATE POLICY "payment_methods: public read"
    ON payment_methods FOR SELECT USING (true);

CREATE POLICY "payment_methods: service all"
    ON payment_methods FOR ALL TO service_role
    USING (true) WITH CHECK (true);

-- Seed payment methods (cash always enabled)
INSERT INTO payment_methods (name, code, is_enabled) VALUES
    ('Cash',          'cash',          true),
    ('Bank Transfer', 'bank_transfer', false),
    ('E-Wallet',      'ewallet',       false)
ON CONFLICT (code) DO NOTHING;

-- ── fare_rules ────────────────────────────────────────────────────────────────
-- Floor price per vehicle type. Applies to ALL trip types and ALL counter rounds.
CREATE TABLE IF NOT EXISTS fare_rules (
    id              serial      PRIMARY KEY,
    vehicle_type_id int         NOT NULL UNIQUE REFERENCES vehicle_types(id),
    default_fare    numeric(10,2) NOT NULL DEFAULT 2000,
    currency        text        NOT NULL DEFAULT 'IDR',
    updated_at      timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE fare_rules ENABLE ROW LEVEL SECURITY;

CREATE POLICY "fare_rules: public read"
    ON fare_rules FOR SELECT USING (true);

CREATE POLICY "fare_rules: service all"
    ON fare_rules FOR ALL TO service_role
    USING (true) WITH CHECK (true);

-- Seed fare rules (Rp 2,000 floor for all types)
INSERT INTO fare_rules (vehicle_type_id, default_fare, currency)
SELECT id, 2000, 'IDR' FROM vehicle_types
ON CONFLICT (vehicle_type_id) DO NOTHING;

-- ── app_settings ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS app_settings (
    key        text        PRIMARY KEY,
    value      text        NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE app_settings ENABLE ROW LEVEL SECURITY;

CREATE POLICY "app_settings: public read"
    ON app_settings FOR SELECT USING (true);

CREATE POLICY "app_settings: service all"
    ON app_settings FOR ALL TO service_role
    USING (true) WITH CHECK (true);

-- Seed: 6 rounds → rider gets 3 counters, driver gets 3
INSERT INTO app_settings (key, value) VALUES ('max_negotiation_rounds', '6')
ON CONFLICT (key) DO NOTHING;

-- ── trips ─────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS trips (
    id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    rider_id              uuid        NOT NULL REFERENCES rider_profiles(id) ON DELETE CASCADE,
    driver_id             uuid        REFERENCES driver_profiles(id) ON DELETE SET NULL,
    offered_by            uuid        REFERENCES driver_profiles(id) ON DELETE SET NULL,
    status                trip_status NOT NULL DEFAULT 'requested',
    trip_type             trip_type   NOT NULL DEFAULT 'transport',
    vehicle_type_id       int         NOT NULL REFERENCES vehicle_types(id),
    island_id             int         REFERENCES islands(id) ON DELETE SET NULL,
    pickup_location       geography(Point, 4326),
    dropoff_location      geography(Point, 4326),
    pickup_address        text        NOT NULL,
    dropoff_address       text,
    note                  text,
    fare                  numeric(10,2),
    offered_fare          numeric(10,2),
    payment_method_id     int         REFERENCES payment_methods(id) DEFAULT 1,
    -- Negotiation tracking
    last_offer_by         text        CHECK (last_offer_by IN ('driver', 'rider')),
    offer_round           int         NOT NULL DEFAULT 0,
    driver_counter_count  int         NOT NULL DEFAULT 0,
    rider_counter_count   int         NOT NULL DEFAULT 0,
    -- FCM notification tracking
    notified_driver_id    uuid        REFERENCES driver_profiles(id) ON DELETE SET NULL,
    notified_at           timestamptz,
    notification_attempts int         NOT NULL DEFAULT 0,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_trips_rider_id        ON trips(rider_id);
CREATE INDEX IF NOT EXISTS idx_trips_driver_id       ON trips(driver_id);
CREATE INDEX IF NOT EXISTS idx_trips_status          ON trips(status);
CREATE INDEX IF NOT EXISTS idx_trips_status_island   ON trips(status, island_id);
CREATE INDEX IF NOT EXISTS idx_trips_island_id       ON trips(island_id);
CREATE INDEX IF NOT EXISTS idx_trips_offered_by      ON trips(offered_by);
CREATE INDEX IF NOT EXISTS idx_trips_pickup_location ON trips USING GIST(pickup_location);
CREATE INDEX IF NOT EXISTS idx_trips_last_offer_by
    ON trips(last_offer_by) WHERE status = 'offered';

ALTER TABLE trips ENABLE ROW LEVEL SECURITY;

CREATE POLICY "trips: rider select own"
    ON trips FOR SELECT
    USING (auth.uid() = rider_id);

CREATE POLICY "trips: rider insert own"
    ON trips FOR INSERT
    WITH CHECK (auth.uid() = rider_id);

CREATE POLICY "trips: rider update own"
    ON trips FOR UPDATE
    USING (auth.uid() = rider_id);

-- Driver sees their own trips + requested trips matching their vehicle type + island
CREATE POLICY "trips: driver select relevant"
    ON trips FOR SELECT TO authenticated
    USING (
        auth.uid() = driver_id
        OR auth.uid() = offered_by
        OR (
            status = 'requested'
            AND EXISTS (
                SELECT 1
                FROM driver_profiles dp
                JOIN driver_vehicles dv ON dv.id = dp.active_vehicle_id
                WHERE dp.id = auth.uid()
                  AND dp.island_id = trips.island_id
                  AND dv.vehicle_type_id = trips.vehicle_type_id
                  AND dp.is_online = true
            )
        )
    );

CREATE POLICY "trips: driver update assigned"
    ON trips FOR UPDATE
    USING (auth.uid() = driver_id);

CREATE POLICY "trips: service all"
    ON trips FOR ALL TO service_role
    USING (true) WITH CHECK (true);

-- ── driver_notification_queue ─────────────────────────────────────────────────
-- pg_cron populates; Go notification processor drains via FCM.
CREATE TABLE IF NOT EXISTS driver_notification_queue (
    id         bigserial   PRIMARY KEY,
    trip_id    uuid        NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    driver_id  uuid        NOT NULL REFERENCES driver_profiles(id) ON DELETE CASCADE,
    fcm_token  text        NOT NULL,
    status     text        NOT NULL DEFAULT 'pending'
               CHECK (status IN ('pending', 'sent', 'failed')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_notif_queue_status  ON driver_notification_queue(status);
CREATE INDEX IF NOT EXISTS idx_notif_queue_trip_id ON driver_notification_queue(trip_id);

ALTER TABLE driver_notification_queue ENABLE ROW LEVEL SECURITY;

-- Only the service role (Go server + pg_cron) accesses this table
REVOKE ALL ON driver_notification_queue FROM anon, authenticated;

CREATE POLICY "notif_queue: service all"
    ON driver_notification_queue FOR ALL TO service_role
    USING (true) WITH CHECK (true);

-- ── ratings ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ratings (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id    uuid        NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    rater_id   uuid        NOT NULL,
    ratee_id   uuid        NOT NULL,
    rater_role text        NOT NULL CHECK (rater_role IN ('driver', 'rider')),
    score      int         NOT NULL CHECK (score BETWEEN 1 AND 5),
    comment    text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (trip_id, rater_role)
);

CREATE INDEX IF NOT EXISTS idx_ratings_ratee_id ON ratings(ratee_id);
CREATE INDEX IF NOT EXISTS idx_ratings_trip_id  ON ratings(trip_id);

ALTER TABLE ratings ENABLE ROW LEVEL SECURITY;

-- Only trip participants can read their own ratings
CREATE POLICY "ratings: public read"
    ON ratings FOR SELECT TO authenticated
    USING (auth.uid() = rater_id OR auth.uid() = ratee_id);

CREATE POLICY "ratings: rater insert"
    ON ratings FOR INSERT TO authenticated
    WITH CHECK (auth.uid() = rater_id);

-- ── Realtime publication ──────────────────────────────────────────────────────
-- Required for SearchingViewModel, NegotiationViewModel, WaitingForRiderViewModel,
-- ActiveTripViewModel (both apps), and RealtimeLocationTracker (rider).
ALTER PUBLICATION supabase_realtime ADD TABLE public.trips;
ALTER PUBLICATION supabase_realtime ADD TABLE public.driver_profiles;

-- ── Functions ─────────────────────────────────────────────────────────────────

-- resolve_island_id: used in UpdateLocation handler and RequestRide handler
CREATE OR REPLACE FUNCTION resolve_island_id(lng double precision, lat double precision)
RETURNS int
LANGUAGE sql
STABLE
AS $$
    SELECT id FROM islands
    WHERE boundary IS NOT NULL
      AND ST_Within(
          ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geometry,
          boundary::geometry
      )
    LIMIT 1;
$$;

-- sync_driver_lat_lng: keeps last_lat/last_lng in sync with last_location PostGIS point
-- Used by RealtimeLocationTracker (rider app) which reads last_lat/last_lng via Realtime.
CREATE OR REPLACE FUNCTION sync_driver_lat_lng()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.last_location IS NOT NULL THEN
        NEW.last_lat := ST_Y(NEW.last_location::geometry);
        NEW.last_lng := ST_X(NEW.last_location::geometry);
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER driver_location_sync
    BEFORE UPDATE ON driver_profiles
    FOR EACH ROW
    EXECUTE FUNCTION sync_driver_lat_lng();

-- notify_nearest_driver_on_insert: fires on new trip INSERT.
-- Finds the nearest online driver matching vehicle type + island, queues FCM notification.
CREATE OR REPLACE FUNCTION notify_nearest_driver_on_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_driver_id     uuid;
    v_fcm_token     text;
    v_island_radius int;
BEGIN
    IF NEW.island_id IS NULL OR NEW.pickup_location IS NULL THEN
        RETURN NULL;
    END IF;

    SELECT search_radius_m INTO v_island_radius
    FROM islands WHERE id = NEW.island_id;

    SELECT dp.id, dp.fcm_token
    INTO v_driver_id, v_fcm_token
    FROM driver_profiles dp
    JOIN driver_vehicles dv ON dv.id = dp.active_vehicle_id
    WHERE dp.is_online        = true
      AND dp.island_id        = NEW.island_id
      AND dp.fcm_token        IS NOT NULL
      AND dp.last_location    IS NOT NULL
      AND dv.vehicle_type_id  = NEW.vehicle_type_id
      AND ST_DWithin(dp.last_location, NEW.pickup_location, v_island_radius)
    ORDER BY ST_Distance(dp.last_location, NEW.pickup_location) ASC
    LIMIT 1;

    IF v_driver_id IS NOT NULL THEN
        INSERT INTO driver_notification_queue (trip_id, driver_id, fcm_token)
        VALUES (NEW.id, v_driver_id, v_fcm_token);

        UPDATE trips
        SET notified_driver_id    = v_driver_id,
            notified_at           = now(),
            notification_attempts = 1
        WHERE id = NEW.id;
    END IF;

    RETURN NULL;
END;
$$;

CREATE TRIGGER trip_notify_nearest_driver
    AFTER INSERT ON trips
    FOR EACH ROW
    WHEN (NEW.status = 'requested')
    EXECUTE FUNCTION notify_nearest_driver_on_insert();

-- process_trip_notification_timeouts: called by pg_cron every minute.
-- Retries notification to next nearest driver (3-min timeout).
-- Auto-cancels trip after 5 failed attempts.
CREATE OR REPLACE FUNCTION process_trip_notification_timeouts()
RETURNS void
LANGUAGE plpgsql
AS $$
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
        JOIN driver_vehicles dv ON dv.id = dp.active_vehicle_id
        WHERE dp.is_online        = true
          AND dp.island_id        = r.island_id
          AND dp.fcm_token        IS NOT NULL
          AND dp.last_location    IS NOT NULL
          AND dv.vehicle_type_id  = r.vehicle_type_id
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
$$;

-- Schedule pg_cron job (runs every minute)
SELECT cron.unschedule(jobid) FROM cron.job WHERE jobname = 'trip-notification-fallback';
SELECT cron.schedule(
    'trip-notification-fallback',
    '* * * * *',
    'SELECT process_trip_notification_timeouts()'
);

-- refresh_avg_rating: fires after every rating INSERT.
-- Updates avg_rating + rating_count on the ratee's profile.
CREATE OR REPLACE FUNCTION refresh_avg_rating()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.rater_role = 'driver' THEN
        UPDATE rider_profiles
        SET avg_rating   = (SELECT AVG(score)::numeric(3,2) FROM ratings WHERE ratee_id = NEW.ratee_id),
            rating_count = (SELECT COUNT(*) FROM ratings WHERE ratee_id = NEW.ratee_id)
        WHERE id = NEW.ratee_id;
    ELSE
        UPDATE driver_profiles
        SET avg_rating   = (SELECT AVG(score)::numeric(3,2) FROM ratings WHERE ratee_id = NEW.ratee_id),
            rating_count = (SELECT COUNT(*) FROM ratings WHERE ratee_id = NEW.ratee_id)
        WHERE id = NEW.ratee_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER after_rating_insert
    AFTER INSERT ON ratings
    FOR EACH ROW
    EXECUTE FUNCTION refresh_avg_rating();

-- ── Island boundaries ─────────────────────────────────────────────────────────
-- Apply these after the above. Boundaries are real GeoJSON polygons for
-- Kepulauan Talaud (WGS-84, SRID 4326, validated ST_IsValid = true).

UPDATE islands SET boundary = ST_SetSRID(ST_GeomFromGeoJSON('{"type":"Polygon","coordinates":[[[126.843,3.7714],[126.8406,3.774],[126.8392,3.7842],[126.8359,3.789],[126.8349,3.7939],[126.832,3.7969],[126.8288,3.7975],[126.8251,3.8059],[126.8185,3.8086],[126.8164,3.8148],[126.8145,3.8153],[126.8098,3.8247],[126.8059,3.8278],[126.8034,3.834],[126.7936,3.8356],[126.7891,3.8394],[126.7798,3.8411],[126.7616,3.8546],[126.7525,3.8527],[126.7439,3.8429],[126.7428,3.8281],[126.7449,3.8117],[126.7518,3.7995],[126.754,3.7864],[126.765,3.7778],[126.7655,3.7745],[126.7639,3.7729],[126.7674,3.7695],[126.7685,3.7638],[126.7718,3.7604],[126.7763,3.7603],[126.7811,3.7541],[126.7821,3.7498],[126.7882,3.744],[126.7944,3.7418],[126.8031,3.7429],[126.8097,3.7404],[126.8159,3.7359],[126.8211,3.728],[126.8241,3.7281],[126.8352,3.7425],[126.8419,3.7404],[126.8458,3.7482],[126.8515,3.7529],[126.8507,3.7579],[126.843,3.7714]]]}'), 4326) WHERE name = 'Kabaruan';

UPDATE islands SET boundary = ST_SetSRID(ST_GeomFromGeoJSON('{"type":"Polygon","coordinates":[[[126.8201,4.1729],[126.827,4.1852],[126.8306,4.1883],[126.845,4.197],[126.8566,4.2019],[126.8706,4.2164],[126.8717,4.2251],[126.8695,4.2313],[126.8678,4.2339],[126.8617,4.2347],[126.8604,4.2379],[126.8592,4.2454],[126.8613,4.2503],[126.8745,4.2528],[126.882,4.2601],[126.8941,4.2598],[126.8996,4.2622],[126.9039,4.2674],[126.9118,4.2684],[126.9127,4.2704],[126.9095,4.275],[126.9101,4.2797],[126.8997,4.3018],[126.8965,4.3063],[126.8941,4.3048],[126.8922,4.3068],[126.8931,4.3084],[126.8915,4.3126],[126.8876,4.313],[126.8846,4.3178],[126.8861,4.3218],[126.8768,4.3299],[126.8669,4.3422],[126.866,4.344],[126.8671,4.346],[126.8647,4.346],[126.8608,4.3521],[126.8613,4.3536],[126.8557,4.3607],[126.8504,4.3754],[126.8494,4.3901],[126.8522,4.3908],[126.8525,4.3947],[126.8538,4.3953],[126.8523,4.396],[126.8511,4.4057],[126.8522,4.4136],[126.855,4.4148],[126.8586,4.4204],[126.867,4.4239],[126.8698,4.4291],[126.8662,4.4423],[126.8682,4.4563],[126.8666,4.459],[126.8613,4.459],[126.8533,4.4662],[126.8569,4.4813],[126.8556,4.4893],[126.8544,4.49],[126.8515,4.487],[126.8422,4.4895],[126.8373,4.4877],[126.8309,4.4915],[126.8294,4.4946],[126.8312,4.4989],[126.8302,4.503],[126.8166,4.5115],[126.8177,4.5165],[126.8167,4.5215],[126.8143,4.5256],[126.8116,4.5244],[126.8102,4.5254],[126.8101,4.5296],[126.808,4.5337],[126.7945,4.5322],[126.7898,4.5295],[126.7873,4.5248],[126.785,4.5248],[126.7752,4.5294],[126.7744,4.5326],[126.7771,4.5371],[126.7754,4.5388],[126.7614,4.5433],[126.7424,4.5458],[126.7372,4.5426],[126.7248,4.5241],[126.7203,4.51],[126.7213,4.4938],[126.7291,4.4871],[126.7285,4.4842],[126.725,4.4836],[126.7257,4.4777],[126.7232,4.4777],[126.7222,4.4761],[126.7236,4.4747],[126.7299,4.4743],[126.7338,4.4658],[126.734,4.4618],[126.7278,4.4544],[126.7229,4.4516],[126.7176,4.456],[126.7145,4.4499],[126.7185,4.4408],[126.7165,4.4343],[126.7105,4.4276],[126.7079,4.4274],[126.7077,4.4291],[126.7034,4.4287],[126.7062,4.4226],[126.7018,4.4212],[126.6991,4.4124],[126.7029,4.4055],[126.7005,4.4014],[126.696,4.4016],[126.6923,4.3944],[126.693,4.3927],[126.698,4.3928],[126.6993,4.3821],[126.695,4.3762],[126.6871,4.3746],[126.6853,4.3671],[126.6897,4.3588],[126.683,4.3508],[126.6802,4.3345],[126.6814,4.3312],[126.6874,4.328],[126.6923,4.3274],[126.6936,4.3297],[126.6954,4.3279],[126.6961,4.3293],[126.6981,4.328],[126.6988,4.3294],[126.708,4.3222],[126.7072,4.3193],[126.709,4.3168],[126.7065,4.3132],[126.7071,4.3079],[126.7137,4.3068],[126.7155,4.3026],[126.7108,4.2831],[126.7112,4.2776],[126.7091,4.2729],[126.7123,4.2731],[126.7154,4.2703],[126.7172,4.2681],[126.7171,4.2643],[126.7267,4.268],[126.7327,4.2672],[126.7411,4.2616],[126.7471,4.2631],[126.7515,4.2544],[126.7581,4.2476],[126.7749,4.2505],[126.7813,4.2488],[126.7906,4.2397],[126.7934,4.235],[126.7925,4.2301],[126.7884,4.2263],[126.7912,4.2138],[126.7898,4.21],[126.7724,4.1965],[126.7645,4.1878],[126.763,4.1836],[126.76,4.1816],[126.7567,4.1688],[126.7495,4.1535],[126.7503,4.1421],[126.7478,4.1384],[126.7445,4.1377],[126.7416,4.1319],[126.7366,4.1267],[126.7333,4.1165],[126.7343,4.1137],[126.7333,4.1087],[126.7281,4.1036],[126.7216,4.1008],[126.7227,4.0964],[126.7217,4.0937],[126.7157,4.0913],[126.7129,4.0846],[126.7101,4.084],[126.7053,4.0859],[126.6995,4.0818],[126.6966,4.0761],[126.6919,4.0741],[126.6888,4.0752],[126.6866,4.0742],[126.6819,4.0623],[126.6688,4.0443],[126.6673,4.0392],[126.6682,4.0338],[126.6662,4.0284],[126.6697,4.0191],[126.669,4.0145],[126.6708,4.0037],[126.6793,3.9942],[126.685,3.9925],[126.6966,4.0027],[126.7016,4.0045],[126.7118,4.0036],[126.7174,3.9994],[126.7427,3.9928],[126.7627,3.9946],[126.7667,3.9969],[126.767,3.9999],[126.7718,4.0011],[126.7728,4.0075],[126.7746,4.0092],[126.78,4.0065],[126.7832,4.0101],[126.7849,4.0173],[126.7889,4.016],[126.7914,4.0171],[126.7952,4.0237],[126.7934,4.0281],[126.7946,4.0316],[126.8011,4.0332],[126.8063,4.0299],[126.8087,4.0322],[126.8071,4.039],[126.8027,4.041],[126.8017,4.0432],[126.8016,4.0552],[126.8049,4.0625],[126.8031,4.066],[126.806,4.0678],[126.808,4.0751],[126.8049,4.0828],[126.8095,4.09],[126.8083,4.0954],[126.8113,4.0986],[126.8121,4.1049],[126.8049,4.1074],[126.8035,4.1097],[126.8048,4.1243],[126.8007,4.1284],[126.8006,4.1339],[126.796,4.14],[126.7984,4.1437],[126.7969,4.1483],[126.8012,4.1507],[126.8027,4.157],[126.8079,4.1598],[126.8068,4.1642],[126.808,4.1665],[126.818,4.1696],[126.8201,4.1729]]]}'), 4326) WHERE name = 'Karakelang';

UPDATE islands SET boundary = ST_SetSRID(ST_GeomFromGeoJSON('{"type":"Polygon","coordinates":[[[126.7041,3.8244],[126.693,3.8443],[126.6884,3.8586],[126.6899,3.8624],[126.6859,3.8633],[126.6853,3.8694],[126.6872,3.8716],[126.693,3.8682],[126.6975,3.8727],[126.7006,3.873],[126.7066,3.8815],[126.7171,3.8883],[126.7196,3.8923],[126.7185,3.8942],[126.7211,3.8949],[126.7226,3.8986],[126.7215,3.9032],[126.7229,3.9095],[126.7199,3.9195],[126.7154,3.9256],[126.6993,3.9385],[126.6902,3.941],[126.6707,3.9564],[126.6647,3.959],[126.6566,3.9688],[126.6532,3.9701],[126.647,3.9769],[126.642,3.9904],[126.6436,4.0069],[126.6407,4.0233],[126.6339,4.0304],[126.622,4.0373],[126.6206,4.0421],[126.6172,4.0437],[126.6113,4.0432],[126.6085,4.0416],[126.6083,4.0395],[126.6163,4.0301],[126.6176,4.0208],[126.6138,4.0159],[126.6134,4.0134],[126.6153,4.0115],[126.6121,4.0014],[126.6151,3.9981],[126.6203,3.997],[126.6213,3.9923],[126.6135,3.9833],[126.6185,3.9835],[126.6205,3.9859],[126.6287,3.9833],[126.6336,3.9706],[126.6368,3.9695],[126.635,3.9582],[126.6377,3.9508],[126.6455,3.9455],[126.6487,3.9365],[126.6506,3.9359],[126.6551,3.9392],[126.6585,3.9395],[126.6612,3.9368],[126.6606,3.9335],[126.6635,3.934],[126.6684,3.9302],[126.6674,3.9286],[126.6699,3.9166],[126.6643,3.9075],[126.6657,3.8938],[126.6617,3.8829],[126.6635,3.8751],[126.6668,3.8735],[126.6693,3.8694],[126.6701,3.8622],[126.6729,3.8618],[126.6765,3.8548],[126.675,3.836],[126.6848,3.8205],[126.6839,3.8169],[126.6811,3.8146],[126.6827,3.8125],[126.6932,3.8097],[126.701,3.8117],[126.7055,3.8154],[126.7062,3.8188],[126.7041,3.8244]]]}'), 4326) WHERE name = 'Salibabu';

UPDATE islands SET boundary = ST_SetSRID(ST_GeomFromGeoJSON('{"type":"Polygon","coordinates":[[[126.7125,3.9463],[126.7105,3.9464],[126.7089,3.9436],[126.7148,3.9387],[126.718,3.9445],[126.7125,3.9463]]]}'), 4326) WHERE name = 'Sara Besar';

UPDATE islands SET boundary = ST_SetSRID(ST_GeomFromGeoJSON('{"type":"Polygon","coordinates":[[[126.7077,3.9535],[126.7125,3.9529],[126.7134,3.9551],[126.7103,3.9558],[126.7077,3.9535]]]}'), 4326) WHERE name = 'Sara Kecil';
