-- Threshold decay (issue #485, section 5): new per-athlete settings. Off for everyone for now,
-- including new athletes - this slice only wires the mechanism, with no staleness-display/
-- settings-API/daily-job/frontend support yet; flipping new-athlete default to on is deferred to
-- a later slice (see User.thresholdDecayEnabled's own Javadoc). Matches the Python backend's
-- accounts 0025 migration.
ALTER TABLE users ADD COLUMN threshold_decay_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE users ADD COLUMN threshold_decay_tau_days INTEGER NOT NULL DEFAULT 25;
ALTER TABLE users ADD COLUMN threshold_decay_min_pct INTEGER NOT NULL DEFAULT 3;
ALTER TABLE users ADD COLUMN threshold_decay_max_pct INTEGER NOT NULL DEFAULT 12;
