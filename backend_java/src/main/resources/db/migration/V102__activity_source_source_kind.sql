-- Issue #503 (phase 3): generalizes running_power_kind into a reusable per-field tag column -
-- "native"/"stryd" for power+run (unchanged meaning), "strap"/"wrist" for heartrate, "pedals"/
-- "trainer"/"watch" for cadence. No DB-level CHECK constraint - the valid value set now depends
-- on the owning row's sensor_kind, which raw SQL can't express cleanly as a single CHECK;
-- validation moves to the application layer, matching Django's own "choices= isn't DB-enforced"
-- convention for this same column.
ALTER TABLE activity_source RENAME COLUMN running_power_kind TO source_kind;
ALTER TABLE activity_source DROP CONSTRAINT activity_source_running_power_kind_check;
