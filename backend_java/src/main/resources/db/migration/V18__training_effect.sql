-- Garmin's Firstbeat-derived training load, from a FIT session message (no GPX/TCX
-- equivalent). Device-computed, never user-settable. Matches the Python backend's
-- 0005_activity_aerobic_training_effect_and_more migration.
ALTER TABLE activity
    ADD COLUMN aerobic_training_effect   DOUBLE PRECISION,
    ADD COLUMN anaerobic_training_effect DOUBLE PRECISION,
    ADD COLUMN training_effect_label     VARCHAR(20) NOT NULL DEFAULT '';
