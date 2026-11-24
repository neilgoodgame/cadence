-- Issue #597: per-kind overrides of users.best_effort_top_n. running_pace gets its own
-- column defaulting to 0 (unlimited) - a pace window can be an embedded split within a much
-- longer activity and almost never ranks in an athlete's all-time top 10, so the shared
-- default was silently trimming it away. Power has no known equivalent gap, so its override
-- is nullable and defaults to NULL ("use best_effort_top_n", unchanged behavior) rather than
-- changing anyone's existing retention.
ALTER TABLE users ADD COLUMN best_effort_top_n_running_pace INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN best_effort_top_n_power INTEGER;

-- Supports the date-range query (GET /v1/athletes/{id}/best-efforts?start_date=&end_date=,
-- issue #582) directly, rather than via the existing (athlete_id, kind) index plus a row-by-row
-- date filter. Matters more now that running_pace defaults to unlimited retention for every
-- athlete (issue #597) - that query scans substantially more rows per athlete than before.
CREATE INDEX idx_best_effort_athlete_kind_window_date ON best_effort (athlete_id, kind, window_label, date);
