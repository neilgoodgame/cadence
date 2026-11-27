-- Issue #616: an activity excluded from threshold detection for one field - never a candidate
-- in any scan, and winning over an accepted_threshold_candidate for the same (field, activity).
-- Mirrors accepted_threshold_candidate (V60), plus set_by for provenance.
CREATE TABLE excluded_threshold_candidate (
    id          BIGSERIAL PRIMARY KEY,
    athlete_id  VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    field       VARCHAR(20)  NOT NULL CHECK (field IN ('ftp', 'critical_run_power', 'threshold_pace')),
    activity_id VARCHAR(40)  NOT NULL REFERENCES activity (id) ON DELETE CASCADE,
    set_by_id   VARCHAR(40)  REFERENCES users (id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT unique_excluded_threshold_candidate UNIQUE (athlete_id, field, activity_id)
);
