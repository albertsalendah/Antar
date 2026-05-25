-- Migration: 015_ratings_and_earnings
-- Ratings table, avg_rating cache columns, auto-update trigger

-- ── ratings ───────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS ratings (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    trip_id     uuid        NOT NULL REFERENCES trips(id) ON DELETE CASCADE,
    rater_id    uuid        NOT NULL,
    ratee_id    uuid        NOT NULL,
    rater_role  text        NOT NULL CHECK (rater_role IN ('driver','rider')),
    score       int         NOT NULL CHECK (score BETWEEN 1 AND 5),
    comment     text,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (trip_id, rater_role)
);

CREATE INDEX idx_ratings_ratee_id ON ratings(ratee_id);
CREATE INDEX idx_ratings_trip_id  ON ratings(trip_id);

ALTER TABLE ratings ENABLE ROW LEVEL SECURITY;

CREATE POLICY "ratings: rater insert"
    ON ratings FOR INSERT WITH CHECK (auth.uid() = rater_id);

CREATE POLICY "ratings: public read"
    ON ratings FOR SELECT USING (true);

-- ── avg_rating cache ──────────────────────────────────────────────────────────
ALTER TABLE driver_profiles
    ADD COLUMN IF NOT EXISTS avg_rating   numeric(3,2),
    ADD COLUMN IF NOT EXISTS rating_count int NOT NULL DEFAULT 0;

ALTER TABLE rider_profiles
    ADD COLUMN IF NOT EXISTS avg_rating   numeric(3,2),
    ADD COLUMN IF NOT EXISTS rating_count int NOT NULL DEFAULT 0;

-- ── Auto-update trigger ───────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION refresh_avg_rating()
RETURNS TRIGGER AS $$
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
$$ LANGUAGE plpgsql;

CREATE TRIGGER after_rating_insert
    AFTER INSERT ON ratings
    FOR EACH ROW
    EXECUTE FUNCTION refresh_avg_rating();
