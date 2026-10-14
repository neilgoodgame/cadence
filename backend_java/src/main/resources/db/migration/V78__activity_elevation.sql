-- Activity sections: a run, walk or ride's altitude resampled every 10m (the same series a course
-- stores - see V75), built from its records on the first sections read. Its own table so activity
-- lists never load it. Matches the Python backend's activities 0023 migration.
CREATE TABLE activity_elevation (
    activity_id       VARCHAR(40) PRIMARY KEY REFERENCES activity (id) ON DELETE CASCADE,
    elevation_quality VARCHAR(10) NOT NULL,
    elevation_series  JSONB       NOT NULL DEFAULT '[]'
);
