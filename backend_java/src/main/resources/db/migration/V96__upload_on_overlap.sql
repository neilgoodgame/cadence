-- Issue #498: a single (non-batch) upload's own on_overlap override, null when the request
-- didn't specify one. UploadBatch.overlap_handling already carries this for a batch upload,
-- resolved once at batch creation - this is the equivalent for the one upload endpoint that
-- previously had no way to carry a per-request override at all.
ALTER TABLE upload ADD COLUMN on_overlap VARCHAR(10)
    CHECK (on_overlap IN ('off', 'flag', 'mark', 'merge'));
