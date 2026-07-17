-- Batch children whose FIT file holds no activity data (Garmin export metadata stubs)
-- are skipped rather than failed.
ALTER TABLE upload DROP CONSTRAINT upload_status_check;
ALTER TABLE upload ADD CONSTRAINT upload_status_check
    CHECK (status IN ('queued', 'processing', 'ready', 'failed', 'duplicate', 'skipped'));
