-- Governs a *new import's* laps only (WorkoutAutoMatchService.attemptMatch, right after a
-- match is found) - not retroactive. 'matched_workout' derives laps from the matched Workout's
-- own step boundaries (LapDerivationService) instead of the device's own FIT-file lap markers,
-- since a device lap and a workout step don't reliably line up 1:1. 'original' keeps today's
-- behavior - laps straight from the file, unlinked to any workout step.
ALTER TABLE users ADD COLUMN lap_source VARCHAR(20) NOT NULL DEFAULT 'matched_workout'
    CHECK (lap_source IN ('matched_workout', 'original'));

-- Set only for a lap derived from a matched Workout's own step boundaries - null for a
-- device-FIT-parsed lap, an unmatched activity, or a trailing/leading segment outside the
-- workout's own steps. workout_step rows are never fetched by id elsewhere, so no app-level
-- id-format concern with a plain bigint FK here.
ALTER TABLE lap ADD COLUMN workout_step_id BIGINT REFERENCES workout_step(id) ON DELETE SET NULL;

-- 1-based - which repetition of workout_step_id's containing repeat group this lap came from
-- (a repeated step is one DB row regardless of how many times it repeats, so multiple laps
-- legitimately share the same workout_step_id).
ALTER TABLE lap ADD COLUMN repeat_index INTEGER;
