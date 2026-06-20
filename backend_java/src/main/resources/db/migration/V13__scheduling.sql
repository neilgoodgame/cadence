CREATE TABLE scheduled_workout (
    id            VARCHAR(40)  PRIMARY KEY,
    workout_id    VARCHAR(40)  NOT NULL REFERENCES workout (id) ON DELETE CASCADE,
    athlete_id    VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    assigned_by   VARCHAR(40)  REFERENCES users (id) ON DELETE SET NULL,
    date          DATE         NOT NULL,
    time_of_day   VARCHAR(3)   CHECK (time_of_day IS NULL OR time_of_day IN ('AM', 'MID', 'PM')),
    status        VARCHAR(10)  NOT NULL DEFAULT 'planned' CHECK (status IN ('planned', 'completed', 'missed')),
    activity_id   VARCHAR(40)  REFERENCES activity (id) ON DELETE SET NULL
);

CREATE INDEX idx_scheduled_workout_athlete_date ON scheduled_workout (athlete_id, date);
