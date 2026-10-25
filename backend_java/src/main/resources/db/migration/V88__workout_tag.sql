-- Workouts now share the same Tag catalog activities use (see V9__activities_core.sql's
-- tag/activity_tag) - a tag named "Threshold" is one row, usable by both. Existing workout tag
-- data (the dropped tags column's raw JSON strings) is intentionally not migrated - re-tag via
-- the web app/MCP instead.
ALTER TABLE workout DROP COLUMN tags;

CREATE TABLE workout_tag (
    id         BIGSERIAL   PRIMARY KEY,
    workout_id VARCHAR(40) NOT NULL REFERENCES workout (id) ON DELETE CASCADE,
    tag_id     VARCHAR(40) NOT NULL REFERENCES tag (id) ON DELETE CASCADE,

    CONSTRAINT unique_workout_tag UNIQUE (workout_id, tag_id)
);

CREATE INDEX idx_workout_tag_tag ON workout_tag (tag_id);
