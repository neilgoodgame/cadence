-- A comment on an activity, from the athlete or anyone with read access to their data
-- (a coach or viewer share) - a lightweight social feature, gated by read access rather
-- than write access. Role (athlete/coach/viewer) is derived at read time from the
-- relationship to the activity's owner, not stored. Matches the Python backend's
-- activities 0011 migration's ActivityComment model.
CREATE TABLE activity_comment (
    id            VARCHAR(40)  PRIMARY KEY,
    activity_id   VARCHAR(40)  NOT NULL REFERENCES activity (id) ON DELETE CASCADE,
    author_id     VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    text          TEXT         NOT NULL,
    created       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX idx_activity_comment_activity ON activity_comment (activity_id, created);
