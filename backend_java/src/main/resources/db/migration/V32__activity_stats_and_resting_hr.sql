-- Activity Analysis "Stats" tab. Matches the Python backend's
-- 0011_activity_avg_cadence_activity_avg_left_balance_pct_and_more migration.
ALTER TABLE activity
    ADD COLUMN max_power            INTEGER,
    ADD COLUMN avg_cadence          INTEGER,
    ADD COLUMN max_cadence          INTEGER,
    ADD COLUMN max_speed            DOUBLE PRECISION,
    ADD COLUMN total_descent        INTEGER,
    ADD COLUMN elevation_min        INTEGER,
    ADD COLUMN elevation_max        INTEGER,
    ADD COLUMN calories             INTEGER,
    ADD COLUMN trimp                DOUBLE PRECISION,
    ADD COLUMN avg_left_balance_pct DOUBLE PRECISION;

-- Optional - only used for the Karvonen heart-rate-reserve % shown alongside the stats
-- above. Matches the Python backend's accounts 0004_user_resting_hr migration.
ALTER TABLE users ADD COLUMN resting_hr INTEGER;
