-- Issue #498: what, if anything, on-ingest overlap detection should do with a newly-imported
-- activity that turns out to overlap one the athlete already has. Defaults to 'off' everywhere
-- so existing import behaviour is unchanged until an athlete opts in.
ALTER TABLE users ADD COLUMN overlap_handling VARCHAR(10) NOT NULL DEFAULT 'off'
    CHECK (overlap_handling IN ('off', 'flag', 'mark', 'merge'));

-- Resolved once at batch creation (the request's own on_overlap, else the athlete's default
-- above) - see UploadService.createBatch - so it's never null past that point.
ALTER TABLE upload_batch ADD COLUMN on_overlap VARCHAR(10) NOT NULL DEFAULT 'off'
    CHECK (on_overlap IN ('off', 'flag', 'mark', 'merge'));

-- What detection actually did for this one upload's resulting activity, if anything - null
-- whenever detection didn't run (on_overlap = off) or found no overlap.
ALTER TABLE upload ADD COLUMN overlap_other_activity_id VARCHAR(40) REFERENCES activity (id) ON DELETE SET NULL;
ALTER TABLE upload ADD COLUMN overlap_action VARCHAR(25)
    CHECK (overlap_action IN ('flagged', 'marked', 'merged', 'merge_skipped_low_score'));
