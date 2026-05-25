-- Migration: 013_phase2_trip_negotiation_fare_payment
-- Phase 2.1 — Driver trip endpoints, price negotiation, fare rules, payment methods

-- ── 1. Extend trip_status enum ────────────────────────────────────────────────
-- offered  : one driver has locked the trip and proposed a price
-- agreed   : rider accepted the price, driver is confirmed
ALTER TYPE trip_status ADD VALUE IF NOT EXISTS 'offered' AFTER 'requested';
ALTER TYPE trip_status ADD VALUE IF NOT EXISTS 'agreed'  AFTER 'offered';

-- ── 2. trip_type enum ─────────────────────────────────────────────────────────
-- transport : normal pickup and dropoff
-- errand    : driver buys/fetches something on behalf of rider
DO $$ BEGIN
    CREATE TYPE trip_type AS ENUM ('transport', 'errand');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ── 3. payment_methods ────────────────────────────────────────────────────────
-- Admin enables non-cash methods from the panel; cash is always on.
-- No code changes needed to activate a new method — just flip is_enabled.
CREATE TABLE IF NOT EXISTS payment_methods (
    id         serial      PRIMARY KEY,
    name       text        NOT NULL,
    code       text        NOT NULL UNIQUE,
    is_enabled boolean     NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO payment_methods (name, code, is_enabled) VALUES
    ('Cash',          'cash',          true),
    ('Bank Transfer', 'bank_transfer', false),
    ('E-Wallet',      'ewallet',       false)
ON CONFLICT (code) DO NOTHING;

ALTER TABLE payment_methods ENABLE ROW LEVEL SECURITY;

CREATE POLICY "payment_methods: public read"
    ON payment_methods FOR SELECT USING (true);

CREATE POLICY "payment_methods: service all"
    ON payment_methods FOR ALL USING (true) WITH CHECK (true);

-- ── 4. Fix fare_rules ─────────────────────────────────────────────────────────
-- Replace the old distance-based columns with a single flat default_fare.
-- Enforcement rules (applied in Go handler):
--   transport trips : offered_fare must be >= default_fare
--   errand trips    : no floor — driver prices freely
DROP POLICY IF EXISTS "Anyone can read fare_rules" ON fare_rules;
DROP POLICY IF EXISTS "Server can read fare_rules"  ON fare_rules;

ALTER TABLE fare_rules
    DROP COLUMN IF EXISTS base_fare,
    DROP COLUMN IF EXISTS price_per_km,
    DROP COLUMN IF EXISTS is_active,
    ADD COLUMN IF NOT EXISTS default_fare numeric(10,2) NOT NULL DEFAULT 2000;

CREATE POLICY "fare_rules: public read"
    ON fare_rules FOR SELECT USING (true);

CREATE POLICY "fare_rules: service all"
    ON fare_rules FOR ALL USING (true) WITH CHECK (true);

-- Seed Rp.2000 default for any vehicle types without a rule
INSERT INTO fare_rules (vehicle_type_id, default_fare, currency)
SELECT id, 2000, 'IDR' FROM vehicle_types
ON CONFLICT (vehicle_type_id) DO NOTHING;

-- ── 5. Extend trips table ─────────────────────────────────────────────────────
ALTER TABLE trips
    ADD COLUMN IF NOT EXISTS trip_type         trip_type    NOT NULL DEFAULT 'transport',
    ADD COLUMN IF NOT EXISTS note              text,
    ADD COLUMN IF NOT EXISTS offered_fare      numeric(10,2),
    ADD COLUMN IF NOT EXISTS offered_by        uuid         REFERENCES driver_profiles(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS payment_method_id int          REFERENCES payment_methods(id) DEFAULT 1;

-- ── 6. Indexes ────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_trips_status_island ON trips(status, island_id);
CREATE INDEX IF NOT EXISTS idx_trips_offered_by    ON trips(offered_by);
