-- Which leaf step kinds counted toward the correlation for a given scan - chosen once, per
-- scan, by whoever triggered it (see WorkoutMatchScanController), not an athlete-wide
-- preference. Same JSONB shape as workout.tags.
ALTER TABLE workout_match_scan
    ADD COLUMN excluded_step_kinds JSONB NOT NULL DEFAULT '[]';
