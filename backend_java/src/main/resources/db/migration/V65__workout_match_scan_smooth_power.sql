-- The match-scan can now optionally smooth the power stream before correlating against the
-- workout's plan, filtering real second-to-second terrain/stride noise that otherwise dilutes
-- a correlation even on a textbook-correct match (see WorkoutMatchScanService's Javadoc).
-- Matches the Python backend's workouts 0008 migration exactly.
ALTER TABLE workout_match_scan
    ADD COLUMN smooth_power BOOLEAN NOT NULL DEFAULT false;
