-- Duplicate activities: the same session recorded by two devices (e.g. Zwift with
-- elevation vs. a head unit with cycling dynamics). A duplicate points at its primary
-- via primary_activity_id; only the primary counts toward training load. Deleting the
-- primary frees its duplicates (SET NULL) rather than deleting them - they are full
-- activities in their own right, unlike multisport legs.
ALTER TABLE activity
    ADD COLUMN primary_activity_id VARCHAR(40) REFERENCES activity (id) ON DELETE SET NULL;

CREATE INDEX idx_activity_primary ON activity (primary_activity_id);
