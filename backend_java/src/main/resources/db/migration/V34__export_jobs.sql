-- Tracks the "export my data" background job (Preferences > Export data). One row per
-- athlete: starting a new export replaces the previous row and file (see ExportController),
-- so there is no history to keep and no cleanup job is needed.
CREATE TABLE export_job (
    id               VARCHAR(40)  PRIMARY KEY,
    athlete_id       VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status           VARCHAR(20)  NOT NULL,
    file_path        VARCHAR(255),
    file_size_bytes  BIGINT,
    error_message    TEXT,
    created_at       TIMESTAMPTZ  NOT NULL,
    completed_at     TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_export_job_athlete ON export_job (athlete_id);
