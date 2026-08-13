-- Retires the per-activity threshold snapshot/suggestion columns (V41/V42) now that
-- ThresholdHistory (V43) is the sole source of truth: an activity's effective threshold is a
-- ledger lookup, not a stored copy.
ALTER TABLE activity DROP COLUMN ftp_snapshot;
ALTER TABLE activity DROP COLUMN critical_run_power_snapshot;
ALTER TABLE activity DROP COLUMN threshold_pace_snapshot;
ALTER TABLE activity DROP COLUMN suggested_ftp;
ALTER TABLE activity DROP COLUMN suggested_critical_run_power;
ALTER TABLE activity DROP COLUMN suggested_threshold_pace;
ALTER TABLE activity DROP COLUMN threshold_checked;

ALTER TABLE import_job ADD COLUMN threshold_history_imported INTEGER NOT NULL DEFAULT 0;
