-- Two independent heat-confound severity tiers (warm/hot), replacing the single decoupling_hot
-- boolean's old air-OR-core logic with air/skin-only thresholds, both athlete-configurable.
-- Matches the Python backend's accounts 0016 / activities 0022 migrations exactly.
ALTER TABLE users
    ADD COLUMN decoupling_warm_air_temp  DOUBLE PRECISION NOT NULL DEFAULT 25.0,
    ADD COLUMN decoupling_warm_skin_temp DOUBLE PRECISION NOT NULL DEFAULT 33.0,
    ADD COLUMN decoupling_hot_air_temp   DOUBLE PRECISION NOT NULL DEFAULT 30.0,
    ADD COLUMN decoupling_hot_skin_temp  DOUBLE PRECISION NOT NULL DEFAULT 34.0;

ALTER TABLE activity
    ADD COLUMN decoupling_avg_skin DOUBLE PRECISION,
    ADD COLUMN decoupling_warm    BOOLEAN NOT NULL DEFAULT false;
