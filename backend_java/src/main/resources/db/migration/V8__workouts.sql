CREATE TABLE workout (
    id          VARCHAR(40)  PRIMARY KEY,
    created_by  VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        VARCHAR(200) NOT NULL,
    sport       VARCHAR(10)  NOT NULL CHECK (sport IN ('bike', 'run')),
    type        VARCHAR(50)  NOT NULL DEFAULT '',
    duration    INTEGER      NOT NULL DEFAULT 0,
    tss         INTEGER      NOT NULL DEFAULT 0
);

CREATE INDEX idx_workout_created_by ON workout (created_by);

CREATE TABLE workout_step (
    id          BIGSERIAL    PRIMARY KEY,
    workout_id  VARCHAR(40)  NOT NULL REFERENCES workout (id) ON DELETE CASCADE,
    step_order  INTEGER      NOT NULL,
    kind        VARCHAR(10)  NOT NULL CHECK (kind IN ('warmup', 'block', 'rec', 'cool')),
    end_type    VARCHAR(10)  NOT NULL CHECK (end_type IN ('time', 'distance', 'manual')),
    duration    INTEGER,
    distance    INTEGER,
    target_pct  DOUBLE PRECISION,
    repeat      INTEGER      NOT NULL DEFAULT 1
);

CREATE INDEX idx_workout_step_workout ON workout_step (workout_id, step_order);
