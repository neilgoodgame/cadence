-- Replace the one-best-per-window constraint with one-best-per-(window, activity),
-- allowing up to N ranked rows per window (trimmed to 5 by the processing pipeline).
ALTER TABLE best_effort DROP CONSTRAINT unique_athlete_kind_window;
ALTER TABLE best_effort ADD CONSTRAINT unique_athlete_kind_window_activity
    UNIQUE (athlete_id, kind, window_label, activity_id);
