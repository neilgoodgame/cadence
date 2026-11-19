-- Issue #542: an "attach" merge (MergeCopyMode.ATTACH) wrote no activity_merge row at all, so
-- it could never be found (get_merge), undone (unmerge_activity), or detached. copy_mode records
-- which merge_service path produced the row; attached_source_id is the ActivitySource it
-- created (needed so undo/detach can find it); demoted_source_id is set only when the attach
-- auto-promoted to primary at merge time - the id of the snapshot source the swap created for
-- the *former* primary, so undo knows exactly what to swap back to.
ALTER TABLE activity_merge ALTER COLUMN null_policy DROP NOT NULL;
ALTER TABLE activity_merge DROP CONSTRAINT activity_merge_null_policy_check;
ALTER TABLE activity_merge ADD CONSTRAINT activity_merge_null_policy_check
    CHECK (null_policy IS NULL OR null_policy IN ('overwrite', 'keep_original'));

ALTER TABLE activity_merge ADD COLUMN copy_mode VARCHAR(10) NOT NULL DEFAULT 'overwrite'
    CHECK (copy_mode IN ('overwrite', 'attach'));
ALTER TABLE activity_merge ADD COLUMN attached_source_id VARCHAR(40)
    REFERENCES activity_source (id) ON DELETE SET NULL;
ALTER TABLE activity_merge ADD COLUMN demoted_source_id VARCHAR(40)
    REFERENCES activity_source (id) ON DELETE SET NULL;

CREATE INDEX idx_activity_merge_attached_source ON activity_merge (attached_source_id);
