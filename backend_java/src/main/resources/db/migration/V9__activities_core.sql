CREATE TABLE activity (
    id                  VARCHAR(40)   PRIMARY KEY,
    athlete_id          VARCHAR(40)   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    sport               VARCHAR(10)   NOT NULL CHECK (sport IN ('bike', 'run', 'swim', 'walk')),
    environment         VARCHAR(10)   NOT NULL DEFAULT 'outdoor' CHECK (environment IN ('outdoor', 'indoor')),
    has_gps             BOOLEAN       NOT NULL DEFAULT FALSE,
    name                VARCHAR(200)  NOT NULL,
    start_date          TIMESTAMPTZ   NOT NULL,
    source              VARCHAR(100)  NOT NULL DEFAULT '',
    moving_time         INTEGER       NOT NULL DEFAULT 0,
    distance_km         DOUBLE PRECISION NOT NULL DEFAULT 0,
    distance_source     VARCHAR(10)   NOT NULL DEFAULT 'gps' CHECK (distance_source IN ('gps', 'footpod', 'trainer', 'manual')),
    avg_power           INTEGER,
    norm_power          INTEGER,
    intensity           DOUBLE PRECISION,
    tss                 INTEGER       NOT NULL DEFAULT 0,
    avg_hr              INTEGER,
    max_hr              INTEGER,
    ascent              INTEGER,
    start_weight_kg     DOUBLE PRECISION,
    end_weight_kg       DOUBLE PRECISION,
    fluids_ml           INTEGER,
    workout_id          VARCHAR(40)   REFERENCES workout (id) ON DELETE SET NULL,
    bike_id             VARCHAR(40)   REFERENCES bike (id) ON DELETE SET NULL,
    shoe_id             VARCHAR(40)   REFERENCES shoe (id) ON DELETE SET NULL
);

CREATE INDEX idx_activity_athlete_start_date ON activity (athlete_id, start_date DESC);
CREATE INDEX idx_activity_workout ON activity (workout_id);

CREATE TABLE lap (
    id            BIGSERIAL    PRIMARY KEY,
    activity_id   VARCHAR(40)  NOT NULL REFERENCES activity (id) ON DELETE CASCADE,
    lap_index     INTEGER      NOT NULL,
    duration      INTEGER      NOT NULL,
    distance_km   DOUBLE PRECISION NOT NULL,
    avg_hr        INTEGER,
    avg_power     INTEGER
);

CREATE INDEX idx_lap_activity ON lap (activity_id, lap_index);

CREATE TABLE tag (
    id          VARCHAR(40)  PRIMARY KEY,
    athlete_id  VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    origin      VARCHAR(10)  NOT NULL DEFAULT 'manual' CHECK (origin IN ('manual', 'auto')),
    color       VARCHAR(20)  NOT NULL DEFAULT '',

    CONSTRAINT unique_athlete_tag_name UNIQUE (athlete_id, name)
);

CREATE TABLE activity_tag (
    id            BIGSERIAL    PRIMARY KEY,
    activity_id   VARCHAR(40)  NOT NULL REFERENCES activity (id) ON DELETE CASCADE,
    tag_id        VARCHAR(40)  NOT NULL REFERENCES tag (id) ON DELETE CASCADE,

    CONSTRAINT unique_activity_tag UNIQUE (activity_id, tag_id)
);

CREATE INDEX idx_activity_tag_tag ON activity_tag (tag_id);
