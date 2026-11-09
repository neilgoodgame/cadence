-- The threshold-decay load term's per-activity histograms (issue #485, section 2.3): seconds
-- spent at each whole watt of 30s-smoothed power, and at each whole bpm of heart rate. Its own
-- table so activity lists never load it. Built lazily on first read, or at ingest/recompute-
-- stats. Matches the Python backend's activities 0034 migration.
CREATE TABLE activity_intensity_histogram (
    activity_id VARCHAR(40) PRIMARY KEY REFERENCES activity (id) ON DELETE CASCADE,
    power_30s   JSONB,
    heartrate   JSONB
);
