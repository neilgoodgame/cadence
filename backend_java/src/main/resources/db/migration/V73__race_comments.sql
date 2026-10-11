-- Coach/athlete comments on a race plan - mirrors activity_comment's shape, except role is
-- stored (a snapshot at post time) rather than derived at read time; see RaceComment's Javadoc.
-- Matches the Python backend's races migration adding this model exactly.
CREATE TABLE race_comment (
    id          VARCHAR(40)   PRIMARY KEY,
    race_id     VARCHAR(40)   NOT NULL REFERENCES race (id) ON DELETE CASCADE,
    author_id   VARCHAR(40)   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role        VARCHAR(10)   NOT NULL CHECK (role IN ('athlete', 'coach')),
    text        VARCHAR(2000) NOT NULL,
    week        INTEGER,
    parent_id   VARCHAR(40)   REFERENCES race_comment (id) ON DELETE CASCADE,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_race_comment_race ON race_comment (race_id, created_at);
