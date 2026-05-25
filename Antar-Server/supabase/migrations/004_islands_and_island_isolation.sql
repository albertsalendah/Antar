-- Migration: islands_and_island_isolation
-- Phase 1.5 — Island-aware matching for archipelago deployments

-- ── islands table ─────────────────────────────────────────────────────────────
-- Populate boundary after running this migration:
--   UPDATE islands
--   SET boundary = ST_SetSRID(ST_GeomFromGeoJSON('{"type":"Polygon","coordinates":[[...]]}'), 4326)
--   WHERE name = 'Your Island Name';

CREATE TABLE IF NOT EXISTS islands (
    id         serial      PRIMARY KEY,
    name       text        NOT NULL UNIQUE,
    boundary   geography(Polygon, 4326),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_islands_boundary ON islands USING GIST(boundary);

-- Seed placeholder rows — rename/extend to match your actual islands
INSERT INTO islands (name) VALUES
    ('Island 1'),
    ('Island 2'),
    ('Island 3')
ON CONFLICT (name) DO NOTHING;

-- ── Add island_id to profiles and trips ───────────────────────────────────────
ALTER TABLE driver_profiles
    ADD COLUMN IF NOT EXISTS island_id int REFERENCES islands(id) ON DELETE SET NULL;

ALTER TABLE rider_profiles
    ADD COLUMN IF NOT EXISTS island_id int REFERENCES islands(id) ON DELETE SET NULL;

ALTER TABLE trips
    ADD COLUMN IF NOT EXISTS island_id int REFERENCES islands(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_trips_island_id ON trips(island_id);

-- ── Helper function used by UpdateLocation handler ────────────────────────────
-- Returns the island id whose boundary polygon contains the given point,
-- or NULL if the point is outside all polygons (open water).
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
