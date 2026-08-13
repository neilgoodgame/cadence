ALTER TABLE users ADD COLUMN threshold_window_days INTEGER NOT NULL DEFAULT 112;
ALTER TABLE users ADD COLUMN threshold_sanity_pct INTEGER NOT NULL DEFAULT 30;

CREATE TABLE threshold_history (
    id                 BIGSERIAL PRIMARY KEY,
    athlete_id         VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    field              VARCHAR(20)  NOT NULL CHECK (field IN ('ftp', 'critical_run_power', 'threshold_pace')),
    value_numeric      INTEGER,
    value_pace         VARCHAR(10)  NOT NULL DEFAULT '',
    source_activity_id VARCHAR(40)  NOT NULL REFERENCES activity (id) ON DELETE CASCADE,
    effective_from     DATE         NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_threshold_history_athlete_field_effective_from
    ON threshold_history (athlete_id, field, effective_from);
