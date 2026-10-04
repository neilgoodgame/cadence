-- The match-scan's candidate pre-filter can now compare by planned distance instead of
-- planned duration (see WorkoutMatchScan.getDurationBasis's own Javadoc). Matches the Python
-- backend's workouts 0007 migration exactly.
ALTER TABLE workout_match_scan
    ADD COLUMN duration_basis VARCHAR(10) NOT NULL DEFAULT 'time';

ALTER TABLE workout_match_scan_candidate
    ADD COLUMN distance_diff_km DOUBLE PRECISION;
