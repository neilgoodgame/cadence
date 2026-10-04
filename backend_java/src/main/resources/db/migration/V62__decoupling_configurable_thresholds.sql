-- Aerobic decoupling qualification thresholds, made athlete-configurable (previously fixed
-- constants - see DecouplingQualificationCalculator). Matches the Python backend's accounts
-- 0015 migration exactly.
ALTER TABLE users
    ADD COLUMN decoupling_vi_limit_bike        DOUBLE PRECISION NOT NULL DEFAULT 1.06,
    ADD COLUMN decoupling_vi_limit_run         DOUBLE PRECISION NOT NULL DEFAULT 1.04,
    ADD COLUMN decoupling_if_limit             DOUBLE PRECISION NOT NULL DEFAULT 0.85,
    ADD COLUMN decoupling_min_steady_minutes   INTEGER          NOT NULL DEFAULT 60;
