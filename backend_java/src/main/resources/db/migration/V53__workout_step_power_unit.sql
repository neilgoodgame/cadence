-- Only meaningful when target_type = 'power' - which unit target_low/target_high are in
-- (percent of FTP/critical_run_power, or absolute watts). Harmless default on every other row,
-- same convention as target2_type's default of 'none' on rows where it doesn't apply.
ALTER TABLE workout_step ADD COLUMN power_unit VARCHAR(10) NOT NULL DEFAULT 'pct_ftp'
    CHECK (power_unit IN ('pct_ftp', 'watts'));
