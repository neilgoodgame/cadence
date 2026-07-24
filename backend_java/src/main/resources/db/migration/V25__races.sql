CREATE TABLE race (
    id          VARCHAR(40)   PRIMARY KEY,
    athlete_id  VARCHAR(40)   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        VARCHAR(200)  NOT NULL,
    date        DATE          NOT NULL,
    sport       VARCHAR(10)   CHECK (sport IS NULL OR sport IN ('bike', 'run', 'swim', 'multisport')),
    distance_km DOUBLE PRECISION,
    goal_time   INTEGER,
    result_time INTEGER,
    activity_id VARCHAR(40)   UNIQUE REFERENCES activity (id) ON DELETE SET NULL,
    url         VARCHAR(2048),
    results_url VARCHAR(2048),
    notes       VARCHAR(500)  NOT NULL DEFAULT ''
);

CREATE INDEX idx_race_athlete_date ON race (athlete_id, date);
