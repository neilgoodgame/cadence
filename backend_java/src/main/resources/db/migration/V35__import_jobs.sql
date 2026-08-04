-- Tracks the "import my data" background job (Preferences > Export data - the reverse of
-- export_job). One row per run, kept as history rather than replaced (like upload/upload_batch),
-- since a run is a meaningful event on its own, not a single large file to keep managing.
CREATE TABLE import_job (
    id                          VARCHAR(40)  PRIMARY KEY,
    athlete_id                  VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status                      VARCHAR(20)  NOT NULL,
    activities_imported         INTEGER      NOT NULL DEFAULT 0,
    races_imported              INTEGER      NOT NULL DEFAULT 0,
    workouts_imported           INTEGER      NOT NULL DEFAULT 0,
    scheduled_workouts_imported INTEGER      NOT NULL DEFAULT 0,
    bikes_imported              INTEGER      NOT NULL DEFAULT 0,
    shoes_imported              INTEGER      NOT NULL DEFAULT 0,
    components_imported         INTEGER      NOT NULL DEFAULT 0,
    items_skipped               INTEGER      NOT NULL DEFAULT 0,
    error_message               TEXT,
    created_at                  TIMESTAMPTZ  NOT NULL,
    completed_at                TIMESTAMPTZ
);

CREATE INDEX idx_import_job_athlete ON import_job (athlete_id, created_at);
