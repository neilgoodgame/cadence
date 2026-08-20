-- Per-athlete ceiling above which a running-power sample is treated as corrupt sensor data
-- (a Stryd/footpod glitch, not a real effort) and dropped before it reaches best efforts,
-- duration curves, normalized power, or threshold history - see RunningPowerSanitizer.
ALTER TABLE users ADD COLUMN max_running_power_watts INTEGER NOT NULL DEFAULT 1000;
