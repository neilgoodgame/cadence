CREATE TABLE duration_curve (
    id            BIGSERIAL    PRIMARY KEY,
    activity_id   VARCHAR(40)  NOT NULL REFERENCES activity (id) ON DELETE CASCADE,
    metric        VARCHAR(10)  NOT NULL CHECK (metric IN ('power', 'heartrate')),
    extends_to    INTEGER      NOT NULL,
    points        JSONB        NOT NULL,

    CONSTRAINT unique_activity_curve_metric UNIQUE (activity_id, metric)
);

CREATE TABLE best_effort (
    id            BIGSERIAL    PRIMARY KEY,
    athlete_id    VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind          VARCHAR(20)  NOT NULL CHECK (kind IN ('cycling_power', 'running_pace', 'running_power')),
    window_label  VARCHAR(20)  NOT NULL,
    value         DOUBLE PRECISION NOT NULL,
    unit          VARCHAR(20)  NOT NULL,
    date          DATE         NOT NULL,
    activity_id   VARCHAR(40)  NOT NULL REFERENCES activity (id) ON DELETE CASCADE,

    CONSTRAINT unique_athlete_kind_window UNIQUE (athlete_id, kind, window_label)
);

CREATE INDEX idx_best_effort_athlete_kind ON best_effort (athlete_id, kind);
