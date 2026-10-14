-- Lets a race opt into a specific tag for its "Key session" key-sessions category, instead of
-- always matching the athlete-wide tag literally named "Key session" (RaceBlockService.keySessions).
ALTER TABLE race
    ADD COLUMN key_session_tag_id VARCHAR(40) REFERENCES tag (id) ON DELETE SET NULL;

CREATE INDEX idx_race_key_session_tag ON race (key_session_tag_id);
