-- Migration: 017_fix_rider_profiles_columns
-- Add missing updated_at and tighten NOT NULL constraints

ALTER TABLE rider_profiles
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

UPDATE rider_profiles SET full_name    = '' WHERE full_name    IS NULL;
UPDATE rider_profiles SET phone_number = '' WHERE phone_number IS NULL;

ALTER TABLE rider_profiles
    ALTER COLUMN full_name    SET NOT NULL,
    ALTER COLUMN phone_number SET NOT NULL;
