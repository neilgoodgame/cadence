-- Opt-in public sharing: a public race's result, splits, course and weather are readable without
-- authentication via /v1/public/races/{id}. Private by default.
ALTER TABLE race ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT FALSE;
