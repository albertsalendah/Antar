-- Migration: rider_profiles_and_trips
-- Phase 1.1 — Rider Module DB setup

-- ── rider_profiles ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS rider_profiles (
    id           uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    full_name    text        NOT NULL,
    phone_number text        NOT NULL,
    email        text,
    avatar_url   text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE rider_profiles ENABLE ROW LEVEL SECURITY;

CREATE POLICY "rider_profiles: owner select"
    ON rider_profiles FOR SELECT
    USING (auth.uid() = id);

CREATE POLICY "rider_profiles: owner update"
    ON rider_profiles FOR UPDATE
    USING (auth.uid() = id);

CREATE POLICY "rider_profiles: service insert"
    ON rider_profiles FOR INSERT
    WITH CHECK (true);

CREATE POLICY "rider_profiles: service select all"
    ON rider_profiles FOR SELECT
    USING (true);

-- ── trips ─────────────────────────────────────────────────────────────────────
CREATE TYPE trip_status AS ENUM (
    'requested',
    'accepted',
    'in_progress',
    'completed',
    'cancelled'
);

CREATE TABLE IF NOT EXISTS trips (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    rider_id         uuid        NOT NULL REFERENCES rider_profiles(id) ON DELETE CASCADE,
    driver_id        uuid        REFERENCES driver_profiles(id) ON DELETE SET NULL,
    status           trip_status NOT NULL DEFAULT 'requested',
    pickup_location  geography(Point, 4326),
    dropoff_location geography(Point, 4326),
    pickup_address   text,
    dropoff_address  text,
    fare             numeric(10, 2),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_trips_rider_id        ON trips(rider_id);
CREATE INDEX idx_trips_driver_id       ON trips(driver_id);
CREATE INDEX idx_trips_status          ON trips(status);
CREATE INDEX idx_trips_pickup_location ON trips USING GIST(pickup_location);

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

CREATE POLICY "trips: driver select relevant"
    ON trips FOR SELECT
    USING (
        auth.uid() = driver_id
        OR status = 'requested'
    );

CREATE POLICY "trips: driver update assigned"
    ON trips FOR UPDATE
    USING (auth.uid() = driver_id);

CREATE POLICY "trips: service all"
    ON trips FOR ALL
    USING (true)
    WITH CHECK (true);
