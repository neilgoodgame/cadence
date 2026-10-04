-- Aerobic decoupling (Pw:HR) + durability (best power once tired). See Activity.java's
-- decoupling* fields and ActivityDurability.java for the full contract; matches the Python
-- backend's activities 0021 migration exactly.
ALTER TABLE activity
    ADD COLUMN decoupling_pct        DOUBLE PRECISION,
    ADD COLUMN ef_first              DOUBLE PRECISION,
    ADD COLUMN ef_second             DOUBLE PRECISION,
    ADD COLUMN steady_seconds        INTEGER,
    ADD COLUMN decoupling_qualified  BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN decoupling_reasons    JSONB   NOT NULL DEFAULT '[]',
    ADD COLUMN decoupling_vi         DOUBLE PRECISION,
    ADD COLUMN decoupling_if         DOUBLE PRECISION,
    ADD COLUMN decoupling_avg_temp   DOUBLE PRECISION,
    ADD COLUMN decoupling_avg_core   DOUBLE PRECISION,
    ADD COLUMN decoupling_hot        BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN decoupling_hr_coverage_pct    DOUBLE PRECISION,
    ADD COLUMN decoupling_power_coverage_pct DOUBLE PRECISION,
    ADD COLUMN decoupling_halves     JSONB   NOT NULL DEFAULT '[]';

CREATE TABLE activity_durability (
    id              BIGSERIAL PRIMARY KEY,
    activity_id     VARCHAR(40)      NOT NULL REFERENCES activity (id) ON DELETE CASCADE,
    athlete_id      VARCHAR(40)      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    sport           VARCHAR(10)      NOT NULL CHECK (sport IN ('bike', 'run')),
    basis           VARCHAR(10)      NOT NULL CHECK (basis IN ('kj', 'minutes')),
    threshold       INTEGER          NOT NULL,
    window_s        INTEGER          NOT NULL,
    power           INTEGER          NOT NULL,
    start_offset_s  INTEGER          NOT NULL,
    activity_date   DATE             NOT NULL,
    CONSTRAINT unique_activity_durability UNIQUE (activity_id, threshold, window_s)
);

CREATE INDEX activity_durability_athlete_sport_date_idx ON activity_durability (athlete_id, sport, activity_date);
