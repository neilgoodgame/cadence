-- Which running-power reading to trust when a FIT file carries both a watch's own native
-- estimate (e.g. Garmin Running Power) and a Stryd footpod reading - see RunningPowerSource's
-- Javadoc. Default matches today's de facto behavior for Stryd users (no change for them);
-- athletes relying on native running power need to switch this explicitly.
ALTER TABLE users ADD COLUMN running_power_source VARCHAR(10) NOT NULL DEFAULT 'stryd'
    CHECK (running_power_source IN ('stryd', 'native'));

-- Which of the two candidate readings a run's own Record.power was actually resolved from -
-- null for every non-FIT/non-run activity, where there's no such ambiguity to record. See
-- Activity.getPowerSource()'s Javadoc.
ALTER TABLE activity ADD COLUMN power_source VARCHAR(10)
    CHECK (power_source IS NULL OR power_source IN ('stryd', 'native'));
