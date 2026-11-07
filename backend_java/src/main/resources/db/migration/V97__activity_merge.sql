-- Issue #501 (phase 1): records one applied merge, so it's auditable and (while the target's
-- upload file is still stored) reversible. Never created for a preview. Named activity_merge,
-- not merge - Postgres 15+ reserves MERGE as a statement keyword.
CREATE TABLE activity_merge (
    id                 VARCHAR(40)   PRIMARY KEY,
    target_activity_id VARCHAR(40)  NOT NULL REFERENCES activity (id) ON DELETE CASCADE,
    source_activity_id VARCHAR(40)  REFERENCES activity (id) ON DELETE SET NULL,
    fields             JSONB        NOT NULL,
    null_policy        VARCHAR(20)  NOT NULL CHECK (null_policy IN ('overwrite', 'keep_original')),
    offset_seconds     INTEGER      NOT NULL,
    alignment_score    DOUBLE PRECISION,
    field_counts       JSONB        NOT NULL,
    source_hidden      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL,
    undone_at          TIMESTAMPTZ
);

CREATE INDEX idx_activity_merge_target ON activity_merge (target_activity_id);
CREATE INDEX idx_activity_merge_source ON activity_merge (source_activity_id);
