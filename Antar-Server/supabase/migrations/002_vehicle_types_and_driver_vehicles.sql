-- Migration: 002_vehicle_types_and_driver_vehicles
-- Introduces a proper vehicle type catalogue and a one-to-many
-- driver → vehicles relationship, replacing the old flat columns.

-- ── vehicle_types ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.vehicle_types (
    id          SERIAL      PRIMARY KEY,
    name        TEXT        NOT NULL UNIQUE,
    code        TEXT        NOT NULL UNIQUE,
    description TEXT,
    is_enabled  BOOLEAN     NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO public.vehicle_types (name, code, description, is_enabled) VALUES
    ('Car',       'car',       'Standard 4-wheeled passenger car',                      true),
    ('Motorbike', 'motorbike', 'Standard 2-wheeled motorcycle',                         true),
    ('Bentor',    'bentor',    'Motorized tricycle with sidecar, common in Manado area', true)
ON CONFLICT (code) DO NOTHING;

ALTER TABLE public.vehicle_types ENABLE ROW LEVEL SECURITY;

-- Authenticated users can read only enabled types.
-- Admin panel writes directly via service role key — no policy needed for that.
CREATE POLICY "Authenticated users can view enabled vehicle types"
    ON public.vehicle_types FOR SELECT
    TO authenticated
    USING (is_enabled = true);

-- ── driver_vehicles ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.driver_vehicles (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    driver_id       UUID        NOT NULL REFERENCES public.driver_profiles(id) ON DELETE CASCADE,
    vehicle_type_id INTEGER     NOT NULL REFERENCES public.vehicle_types(id),
    license_plate   TEXT        NOT NULL,
    make            TEXT,
    model           TEXT,
    year            SMALLINT,
    color           TEXT,
    is_active       BOOLEAN     NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (driver_id, license_plate)
);

CREATE INDEX IF NOT EXISTS idx_driver_vehicles_driver_id
    ON public.driver_vehicles (driver_id);

ALTER TABLE public.driver_vehicles ENABLE ROW LEVEL SECURITY;

CREATE POLICY "Driver can view own vehicles"
    ON public.driver_vehicles FOR SELECT
    USING (auth.uid() = driver_id);

CREATE POLICY "Driver can insert own vehicles"
    ON public.driver_vehicles FOR INSERT
    WITH CHECK (auth.uid() = driver_id);

CREATE POLICY "Driver can update own vehicles"
    ON public.driver_vehicles FOR UPDATE
    USING (auth.uid() = driver_id);

CREATE POLICY "Driver can delete own vehicles"
    ON public.driver_vehicles FOR DELETE
    USING (auth.uid() = driver_id);

-- ── Update driver_profiles ────────────────────────────────────────────────────
-- Remove old flat columns, add pointer to the vehicle currently in use.
ALTER TABLE public.driver_profiles
    DROP COLUMN IF EXISTS vehicle_type,
    DROP COLUMN IF EXISTS license_plate,
    ADD COLUMN IF NOT EXISTS active_vehicle_id UUID
        REFERENCES public.driver_vehicles(id) ON DELETE SET NULL;
