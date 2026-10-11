-- Matches the Python backend's races migration adding the same two fields exactly.
ALTER TABLE race
    ADD COLUMN priority   VARCHAR(1)   NOT NULL DEFAULT 'A' CHECK (priority IN ('A', 'B', 'C')),
    ADD COLUMN course_id  VARCHAR(40)  REFERENCES course (id) ON DELETE SET NULL;

CREATE INDEX idx_race_course ON race (course_id);
