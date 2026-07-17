-- Multisport activities: a parent activity (sport = 'multisport') spans the whole file and
-- links each per-sport leg (including 'transition' segments) via parent_activity_id.
ALTER TABLE activity
    ADD COLUMN parent_activity_id VARCHAR(40) REFERENCES activity (id) ON DELETE CASCADE;

CREATE INDEX idx_activity_parent ON activity (parent_activity_id);

ALTER TABLE activity DROP CONSTRAINT activity_sport_check;
ALTER TABLE activity
    ADD CONSTRAINT activity_sport_check
    CHECK (sport IN ('bike', 'run', 'swim', 'walk', 'multisport', 'transition'));
