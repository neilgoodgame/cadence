-- Recording device from the file's metadata (FIT file_id), e.g. 'Zwift' or
-- 'Garmin Epix Gen2'. Captured at import; empty for formats without it (GPX/TCX)
-- and for activities imported before this column existed.
ALTER TABLE activity
    ADD COLUMN device VARCHAR(100) NOT NULL DEFAULT '';
