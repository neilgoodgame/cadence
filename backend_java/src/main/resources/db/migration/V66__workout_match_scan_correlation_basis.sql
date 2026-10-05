-- The match-scan can now score candidates by structural lap match instead of power
-- correlation - "did the athlete run the prescribed structure" rather than "did they hit the
-- prescribed numbers" (see WorkoutMatchScanService's Javadoc). Matches the Python backend's
-- workouts 0009 migration exactly.
ALTER TABLE workout_match_scan
    ADD COLUMN correlation_basis VARCHAR(10) NOT NULL DEFAULT 'power';
