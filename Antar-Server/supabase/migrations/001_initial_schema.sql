-- Migration: 001_initial_schema
-- Tracks every DB change made to the antar project so the schema
-- is version-controlled alongside the code.
--
-- To apply manually:
--   psql $DATABASE_URL -f supabase/migrations/001_initial_schema.sql
-- Or use the Supabase CLI:
--   supabase db push

-- ── Extensions ────────────────────────────────────────────────────────────────
-- PostGIS is already enabled on this project (installed_version: 3.3.7)

-- ── driver_profiles ───────────────────────────────────────────────────────────
-- Linked 1-to-1 with auth.users via the id foreign key.
CREATE TABLE IF NOT EXISTS public.driver_profiles (
    id            UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    full_name     TEXT,
    phone_number  TEXT,
    license_plate TEXT,
    vehicle_type  TEXT,
    is_online     BOOLEAN          NOT NULL DEFAULT false,
    last_location GEOGRAPHY(POINT, 4326),
    created_at    TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ      NOT NULL DEFAULT now()
);

-- Spatial index — makes "find drivers near me" queries fast
CREATE INDEX IF NOT EXISTS idx_driver_last_location
    ON public.driver_profiles USING GIST (last_location);

-- Row Level Security: a driver may only read/write their own row
ALTER TABLE public.driver_profiles ENABLE ROW LEVEL SECURITY;

-- DROP POLICY IF EXISTS "Driver can view own profile" ON public.driver_profiles;
-- DROP POLICY IF EXISTS "Driver can insert own profile" ON public.driver_profiles;
-- DROP POLICY IF EXISTS "Driver can update own profile" ON public.driver_profiles;

CREATE POLICY "Driver can view own profile"
    ON public.driver_profiles FOR SELECT
    USING (auth.uid() = id);

CREATE POLICY "Driver can insert own profile"
    ON public.driver_profiles FOR INSERT
    WITH CHECK (auth.uid() = id);

CREATE POLICY "Driver can update own profile"
    ON public.driver_profiles FOR UPDATE
    USING (auth.uid() = id);

-- ── rider_profiles ────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.rider_profiles (
    id           UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    full_name    TEXT,
    phone_number TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE public.rider_profiles ENABLE ROW LEVEL SECURITY;

-- DROP POLICY IF EXISTS "Rider can view own profile" ON public.rider_profiles;
-- DROP POLICY IF EXISTS "Rider can insert own profile" ON public.rider_profiles;
-- DROP POLICY IF EXISTS "Rider can update own profile" ON public.rider_profiles;

CREATE POLICY "Rider can view own profile"
    ON public.rider_profiles FOR SELECT
    USING (auth.uid() = id);

CREATE POLICY "Rider can insert own profile"
    ON public.rider_profiles FOR INSERT
    WITH CHECK (auth.uid() = id);

CREATE POLICY "Rider can update own profile"
    ON public.rider_profiles FOR UPDATE
    USING (auth.uid() = id);
