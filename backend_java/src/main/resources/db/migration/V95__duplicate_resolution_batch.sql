-- Issue #498's resolveDuplicatesBatch(..., dryRun=false)/undoBatch MCP tools: one row per
-- apply call (never created for a dry run - nothing happened, nothing to undo), plus one entry
-- row per pair it resolved, recording what changed so undoBatch can reverse it.
CREATE TABLE duplicate_resolution_batch (
    id            VARCHAR(40)  PRIMARY KEY,
    athlete_id    VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    action        VARCHAR(10)  NOT NULL CHECK (action IN ('mark', 'merge')),
    created_by_id VARCHAR(40)  REFERENCES users (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    undone_at     TIMESTAMPTZ
);

-- primary_activity_id is set only when outcome = 'marked' - undoBatch's own check that the
-- activity's current primary still equals it before reverting, so a pair re-resolved (or
-- hand-edited) since this batch applied is never silently clobbered.
CREATE TABLE duplicate_resolution_entry (
    id                  BIGSERIAL    PRIMARY KEY,
    batch_id            VARCHAR(40)  NOT NULL REFERENCES duplicate_resolution_batch (id) ON DELETE CASCADE,
    activity_id         VARCHAR(40)  NOT NULL REFERENCES activity (id) ON DELETE CASCADE,
    other_activity_id   VARCHAR(40)  REFERENCES activity (id) ON DELETE SET NULL,
    outcome             VARCHAR(25)  NOT NULL CHECK (outcome IN ('flagged', 'marked', 'merged', 'merge_skipped_low_score')),
    primary_activity_id VARCHAR(40)  REFERENCES activity (id) ON DELETE SET NULL
);

CREATE INDEX idx_duplicate_resolution_entry_batch ON duplicate_resolution_entry (batch_id);
CREATE INDEX idx_duplicate_resolution_batch_athlete ON duplicate_resolution_batch (athlete_id);
