-- The decoupling steady-window warm-up trim, made athlete-configurable (previously the fixed
-- DecouplingQualificationCalculator.WARMUP_SECONDS = 600 constant) and given a lower 5-minute
-- default. decoupling_use_workout_warmup opts into trimming exactly a matched workout's own
-- warmup step duration instead, when one exists. Matches the Python backend's accounts
-- migration adding the same two fields exactly.
ALTER TABLE users
    ADD COLUMN decoupling_warmup_minutes      INTEGER NOT NULL DEFAULT 5,
    ADD COLUMN decoupling_use_workout_warmup  BOOLEAN NOT NULL DEFAULT false;
