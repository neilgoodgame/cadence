CREATE TABLE upload_batch (
    id              VARCHAR(40)  PRIMARY KEY,
    athlete_id      VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    filename        VARCHAR(255) NOT NULL,
    on_duplicate    VARCHAR(10)  NOT NULL DEFAULT 'skip' CHECK (on_duplicate IN ('skip', 'replace')),
    status          VARCHAR(12)  NOT NULL DEFAULT 'unpacking' CHECK (status IN ('unpacking', 'processing', 'completed', 'failed')),
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    error_code      VARCHAR(100) NOT NULL DEFAULT '',
    error_message   VARCHAR(500) NOT NULL DEFAULT ''
);

CREATE TABLE upload (
    id                VARCHAR(40)  PRIMARY KEY,
    athlete_id        VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    batch_id          VARCHAR(40)  REFERENCES upload_batch (id) ON DELETE CASCADE,
    filename          VARCHAR(255) NOT NULL,
    file_hash         VARCHAR(64)  NOT NULL,
    stored_path       VARCHAR(255) NOT NULL DEFAULT '',
    status            VARCHAR(12)  NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'processing', 'ready', 'failed', 'duplicate')),
    progress          DOUBLE PRECISION,
    activity_id       VARCHAR(40)  REFERENCES activity (id) ON DELETE SET NULL,
    error_code        VARCHAR(100) NOT NULL DEFAULT '',
    error_message     VARCHAR(500) NOT NULL DEFAULT '',
    weight_before_kg  DOUBLE PRECISION,
    weight_after_kg   DOUBLE PRECISION,
    fluids_ml         INTEGER,
    shoe_id           VARCHAR(40)  REFERENCES shoe (id) ON DELETE SET NULL,
    received_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ
);

CREATE INDEX idx_upload_file_hash ON upload (athlete_id, file_hash);
CREATE INDEX idx_upload_batch ON upload (batch_id);
