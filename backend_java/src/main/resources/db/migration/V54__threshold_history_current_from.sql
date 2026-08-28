-- current_from: the date a threshold_history row actually became the recorded current value,
-- distinct from effective_from (the qualifying activity's own date) - see ThresholdHistory's
-- Javadoc. Backfilled to effective_from for existing rows: identical to today's behavior, so
-- this changes nothing until an athlete re-runs "recompute history from oldest" for a field,
-- which repopulates current_from correctly for any row that only became current later via an
-- earlier entry aging out of the window.
ALTER TABLE threshold_history ADD COLUMN current_from DATE;
UPDATE threshold_history SET current_from = effective_from WHERE current_from IS NULL;
ALTER TABLE threshold_history ALTER COLUMN current_from SET NOT NULL;

CREATE INDEX idx_threshold_history_athlete_field_current_from ON threshold_history (athlete_id, field, current_from);
