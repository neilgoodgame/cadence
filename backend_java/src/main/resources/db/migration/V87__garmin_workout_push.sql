-- Workout push to Garmin Connect, applied by a bridge on the athlete's own machine (the official
-- Personal API has no workout routes, and Cadence never holds Garmin credentials). The browser
-- records what should happen as an outbox of operations; the bridge applies them in order and
-- reports the Garmin ids back, which are kept as links so later edits, reschedules and cancels
-- can target the right Garmin objects.
CREATE TABLE garmin_workout_op (
    id                   VARCHAR(40)  PRIMARY KEY,
    owner_id             VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- UPSERT_WORKOUT, SCHEDULE, RESCHEDULE, UNSCHEDULE
    kind                 VARCHAR(20)  NOT NULL,
    -- PENDING, DONE, FAILED
    status               VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    -- Plain columns, not foreign keys: an UNSCHEDULE must outlive the scheduled workout it cancels.
    workout_id           VARCHAR(40)  NOT NULL,
    scheduled_workout_id VARCHAR(40),
    op_date              DATE,
    name                 VARCHAR(200),
    -- The workout as a Garmin .tcx, generated in the browser (it needs the athlete's thresholds).
    tcx                  TEXT,
    garmin_workout_id    BIGINT,
    garmin_schedule_id   BIGINT,
    error                TEXT,
    created              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at         TIMESTAMPTZ
);

CREATE INDEX idx_garmin_workout_op_owner ON garmin_workout_op (owner_id, status, created);

-- Cadence workout -> the Garmin workout it was pushed as.
CREATE TABLE garmin_workout_link (
    id                BIGSERIAL    PRIMARY KEY,
    owner_id          VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    workout_id        VARCHAR(40)  NOT NULL,
    garmin_workout_id BIGINT       NOT NULL,
    pushed_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (owner_id, workout_id)
);

-- Cadence scheduled workout -> its entry on the Garmin calendar.
CREATE TABLE garmin_schedule_link (
    id                   BIGSERIAL    PRIMARY KEY,
    owner_id             VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    scheduled_workout_id VARCHAR(40)  NOT NULL,
    garmin_schedule_id   BIGINT       NOT NULL,
    garmin_workout_id    BIGINT       NOT NULL,
    scheduled_for        DATE         NOT NULL,
    UNIQUE (owner_id, scheduled_workout_id)
);
