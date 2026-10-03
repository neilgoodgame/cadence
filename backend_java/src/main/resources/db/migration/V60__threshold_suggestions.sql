ALTER TABLE users ADD COLUMN threshold_warning_days INTEGER NOT NULL DEFAULT 21;

CREATE TABLE accepted_threshold_candidate (
    id          BIGSERIAL PRIMARY KEY,
    athlete_id  VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    field       VARCHAR(20)  NOT NULL CHECK (field IN ('ftp', 'critical_run_power', 'threshold_pace')),
    activity_id VARCHAR(40)  NOT NULL REFERENCES activity (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT unique_accepted_threshold_candidate UNIQUE (athlete_id, field, activity_id)
);

CREATE TABLE threshold_suggestion_dismissal (
    id         BIGSERIAL PRIMARY KEY,
    athlete_id VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    field      VARCHAR(20)  NOT NULL CHECK (field IN ('ftp', 'critical_run_power', 'threshold_pace')),
    kind       VARCHAR(20)  NOT NULL CHECK (kind IN ('rejected', 'upcoming_drop')),
    key        VARCHAR(80)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT unique_threshold_suggestion_dismissal UNIQUE (athlete_id, field, kind, key)
);
