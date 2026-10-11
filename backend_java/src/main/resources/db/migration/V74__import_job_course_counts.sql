-- Matches the Python backend's dataexport migration adding the same two counters exactly.
ALTER TABLE import_job
    ADD COLUMN courses_imported        INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN race_comments_imported  INTEGER NOT NULL DEFAULT 0;
