-- Issue #498: moving_time is really just the record stream's own span (last Record.t),
-- which understates a ride's true wall-clock duration whenever the device auto-paused
-- recording. elapsed_seconds, populated at ingest from the FIT session's own
-- total_elapsed_time (includes pauses), is the better signal the overlap-detection query
-- needs. Existing rows have no such session data to recover - moving_time is already the
-- best approximation available for them, same as the query-time fallback new code uses.
ALTER TABLE activity
    ADD COLUMN elapsed_seconds INTEGER;

UPDATE activity
SET elapsed_seconds = moving_time
WHERE elapsed_seconds IS NULL;
