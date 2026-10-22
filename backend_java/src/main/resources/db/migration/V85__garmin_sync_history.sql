-- One row per Garmin sync attempt, so the Import screen can show when it last ran and what it
-- did. batch_id is a plain column with SET NULL so clearing import history can't break a run.
CREATE TABLE garmin_sync_run (
    id              VARCHAR(40)  PRIMARY KEY,
    owner_id        VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    started_at      TIMESTAMPTZ  NOT NULL,
    finished_at     TIMESTAMPTZ,
    found           INTEGER      NOT NULL DEFAULT 0,
    already_synced  INTEGER      NOT NULL DEFAULT 0,
    requested       INTEGER      NOT NULL DEFAULT 0,
    failed          INTEGER      NOT NULL DEFAULT 0,
    truncated       BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Short message for a run that errored (e.g. Garmin rejected the key); never the key itself.
    error           TEXT,
    batch_id        VARCHAR(40)  REFERENCES upload_batch (id) ON DELETE SET NULL
);

CREATE INDEX idx_garmin_sync_run_owner ON garmin_sync_run (owner_id, started_at DESC);

-- Every Garmin activity id ever pulled for an athlete. Doubles as the "already synced" check, and
-- deliberately outlives both the Cadence activity (deleting one doesn't bring it back on the next
-- sync) and the run (run history is pruned, these rows are not).
CREATE TABLE garmin_synced_activity (
    id                  BIGSERIAL    PRIMARY KEY,
    owner_id            VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    garmin_activity_id  BIGINT       NOT NULL,
    start_time          TIMESTAMPTZ  NOT NULL,
    run_id              VARCHAR(40)  REFERENCES garmin_sync_run (id) ON DELETE SET NULL,
    created             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (owner_id, garmin_activity_id)
);

CREATE INDEX idx_garmin_synced_activity_run ON garmin_synced_activity (run_id);
