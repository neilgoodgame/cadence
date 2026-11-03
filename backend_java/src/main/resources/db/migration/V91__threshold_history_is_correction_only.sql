-- is_correction_only: a manual entry whose effective_from was backdated but which never became
-- the live/current value - either a later row already existed, or it was already outside
-- threshold_window_days the moment it was saved (issue #493). Still a legitimate historical
-- correction - per-activity rating lookups (ZoneService.referenceFor, filtered on current_from)
-- use it exactly like any other row; it's just excluded from every "what's current" lookup.
ALTER TABLE threshold_history
    ADD COLUMN is_correction_only BOOLEAN NOT NULL DEFAULT false;
