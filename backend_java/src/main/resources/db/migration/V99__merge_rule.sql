-- Issue #501 (phase 4): a saved per-athlete, per-sport rule for automatic merging - which side
-- plays target/source (a "device:<prefix>" pattern matched case-insensitively against
-- Activity.device, e.g. 'device:zwift'), which fields to copy, and the null policy. No row for
-- a sport means its built-in default applies (bike -> target device:zwift, source device:garmin,
-- fields [power], keep_original); other sports have no built-in default.
CREATE TABLE merge_rule (
    id               VARCHAR(40)  PRIMARY KEY,
    athlete_id       VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    sport            VARCHAR(10)  NOT NULL,
    target_pattern   VARCHAR(100) NOT NULL,
    source_pattern   VARCHAR(100) NOT NULL,
    fields           JSONB        NOT NULL,
    null_policy      VARCHAR(20)  NOT NULL CHECK (null_policy IN ('overwrite', 'keep_original')),
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    UNIQUE (athlete_id, sport)
);

-- Set only when outcome = 'merged' - undo_batch's own check that this merge is still active
-- (undone_at IS NULL) before reversing it via unmerge, the same re-resolved-since guard
-- primary_activity_id already gives the 'marked' outcome.
ALTER TABLE duplicate_resolution_entry ADD COLUMN merge_id VARCHAR(40) REFERENCES activity_merge (id) ON DELETE SET NULL;
