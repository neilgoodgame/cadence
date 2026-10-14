-- Course sections (CourseSections): each course stores its raw elevation resampled every 10m,
-- and sections + climbs are calculated from it on every read with the athlete's settings - so
-- the stored climbs go. Matches the Python backend's courses 0002 / accounts 0020 migrations.
-- elevation_quality is '' until the series has been built (a course from before this
-- migration); CourseService.ensureElevationSeries fills it on first read.
ALTER TABLE course DROP COLUMN climbs;
ALTER TABLE course ADD COLUMN elevation_quality VARCHAR(10) NOT NULL DEFAULT '';
ALTER TABLE course ADD COLUMN elevation_series JSONB NOT NULL DEFAULT '[]';

-- Every setting CourseSections.Settings takes, with the same defaults.
ALTER TABLE users ADD COLUMN section_algorithm VARCHAR(20) NOT NULL DEFAULT 'douglas_peucker';
ALTER TABLE users ADD COLUMN section_flat_max_pct DOUBLE PRECISION NOT NULL DEFAULT 1.0;
ALTER TABLE users ADD COLUMN section_steep_min_pct DOUBLE PRECISION NOT NULL DEFAULT 4.0;
ALTER TABLE users ADD COLUMN section_fit_tolerance_m DOUBLE PRECISION NOT NULL DEFAULT 2.0;
ALTER TABLE users ADD COLUMN section_min_length_m DOUBLE PRECISION NOT NULL DEFAULT 250.0;
ALTER TABLE users ADD COLUMN section_keep_min_rise_m DOUBLE PRECISION NOT NULL DEFAULT 5.0;
ALTER TABLE users ADD COLUMN section_spike_window_m DOUBLE PRECISION NOT NULL DEFAULT 30.0;
ALTER TABLE users ADD COLUMN section_smoothing_window_m DOUBLE PRECISION NOT NULL DEFAULT 100.0;
ALTER TABLE users ADD COLUMN section_resample_step_m DOUBLE PRECISION NOT NULL DEFAULT 10.0;
