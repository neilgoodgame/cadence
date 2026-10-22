-- Per-athlete cap on how many activities one Garmin sync pulls (set in Preferences).
-- Garmin documents no rate limit; 100 is our own ceiling.
ALTER TABLE garmin_connection
    ADD COLUMN sync_limit INTEGER NOT NULL DEFAULT 50 CHECK (sync_limit BETWEEN 1 AND 100);
