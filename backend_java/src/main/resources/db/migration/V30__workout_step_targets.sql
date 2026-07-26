ALTER TABLE workout_step
    DROP COLUMN target_pct,
    ALTER COLUMN end_type DROP NOT NULL,
    ADD COLUMN target_type    VARCHAR(10) CHECK (target_type IN ('power', 'hr', 'pace', 'cadence', 'open')),
    ADD COLUMN target_low     DOUBLE PRECISION,
    ADD COLUMN target_high    DOUBLE PRECISION,
    ADD COLUMN target2_type   VARCHAR(10) NOT NULL DEFAULT 'none' CHECK (target2_type IN ('cadence', 'none')),
    ADD COLUMN target2_low    DOUBLE PRECISION,
    ADD COLUMN target2_high   DOUBLE PRECISION,
    ADD COLUMN note           TEXT NOT NULL DEFAULT '',
    ADD COLUMN parent_step_id BIGINT REFERENCES workout_step (id) ON DELETE CASCADE;

ALTER TABLE workout_step DROP CONSTRAINT workout_step_kind_check;
ALTER TABLE workout_step ADD CONSTRAINT workout_step_kind_check
    CHECK (kind IN ('warmup', 'block', 'rec', 'cool', 'repeat'));

CREATE UNIQUE INDEX idx_workout_step_unique_order ON workout_step (workout_id, parent_step_id, step_order);
