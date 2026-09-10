-- Tracks a background scan of a workout's athlete's own unmatched, same-sport activities for
-- likely matches, ranked by Pearson correlation of actual power against the workout's planned
-- %FTP-vs-time curve (see WorkoutMatchScanService). Kept as history (one row per scan run),
-- like import_job, not replaced in place like export_job - a re-scan is a meaningful event
-- worth keeping, not a single file to keep managing.
CREATE TABLE workout_match_scan (
    id                   VARCHAR(40)  PRIMARY KEY,
    workout_id           VARCHAR(40)  NOT NULL REFERENCES workout (id) ON DELETE CASCADE,
    status               VARCHAR(20)  NOT NULL,
    total_candidates     INTEGER,
    processed_candidates INTEGER      NOT NULL DEFAULT 0,
    error_message        TEXT,
    created_at           TIMESTAMPTZ  NOT NULL,
    completed_at         TIMESTAMPTZ
);

CREATE INDEX idx_workout_match_scan_workout ON workout_match_scan (workout_id, created_at);

-- One row per candidate activity that passed the duration pre-filter - not just the top N, so
-- the full ranked list stays inspectable.
CREATE TABLE workout_match_scan_candidate (
    id                     BIGSERIAL    PRIMARY KEY,
    scan_id                VARCHAR(40)  NOT NULL REFERENCES workout_match_scan (id) ON DELETE CASCADE,
    activity_id            VARCHAR(40)  NOT NULL REFERENCES activity (id) ON DELETE CASCADE,
    correlation            DOUBLE PRECISION NOT NULL,
    duration_diff_seconds  INTEGER      NOT NULL,
    coverage               DOUBLE PRECISION NOT NULL,
    implied_ftp            INTEGER
);

CREATE INDEX idx_workout_match_scan_candidate_scan ON workout_match_scan_candidate (scan_id, correlation DESC);
