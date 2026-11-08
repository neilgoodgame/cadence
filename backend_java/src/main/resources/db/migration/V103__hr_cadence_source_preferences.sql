-- Issue #503 (phase 3): HR/cadence equivalents of bike_power_source - which tagged source to
-- prefer as primary when attaching a second heartrate/cadence stream decides a default. 'any'
-- (the default) keeps today's behaviour - no preference applied, attach never auto-swaps. No
-- CHECK constraints, consistent with V102's source_kind rename - validation lives at the
-- application layer.
ALTER TABLE users ADD COLUMN hr_source VARCHAR(10) NOT NULL DEFAULT 'any';
ALTER TABLE users ADD COLUMN cadence_source VARCHAR(10) NOT NULL DEFAULT 'any';

-- Which tagged source this activity's current primary heartrate/cadence stream was last
-- attached/swapped from, if any - mirrors activity.bike_power_source. Null until a manual
-- attach+swap or set_primary_source call sets it; there is no parse-time/device-classification
-- equivalent for either field, so this stays null for the overwhelming majority of activities.
ALTER TABLE activity ADD COLUMN hr_source VARCHAR(10);
ALTER TABLE activity ADD COLUMN cadence_source VARCHAR(10);
