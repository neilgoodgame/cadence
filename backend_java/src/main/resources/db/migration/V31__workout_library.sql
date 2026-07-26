CREATE TABLE workout_folder (
    id         VARCHAR(40)  PRIMARY KEY,
    created_by VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name       VARCHAR(100) NOT NULL,
    UNIQUE (created_by, name)
);

ALTER TABLE workout
    ADD COLUMN folder_id      VARCHAR(40) REFERENCES workout_folder (id) ON DELETE SET NULL,
    ADD COLUMN tags           JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN chart_preview  JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN updated_at     TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE INDEX idx_workout_folder_id ON workout (folder_id);
