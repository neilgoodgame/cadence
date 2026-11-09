-- Issue #503 (phase 1): an activity's power can hold more than one recorded stream (e.g. a
-- Zwift/Kickr copy and a Garmin/pedal copy of the same ride). The primary stream stays in
-- activity's own record rows - every existing calculator/curve/best-effort/threshold path is
-- unchanged. A non-primary stream lives here instead, one row per stream (not one row per
-- second) to keep storage small - see the issue's own sizing rationale.
CREATE TABLE activity_source (
    id          VARCHAR(40)  PRIMARY KEY,
    activity_id VARCHAR(40)  NOT NULL REFERENCES activity (id) ON DELETE CASCADE,
    label       VARCHAR(200),
    device      VARCHAR(100),
    sensor_kind VARCHAR(20)  NOT NULL,
    origin      VARCHAR(10)  NOT NULL CHECK (origin IN ('file', 'attached')),
    created_at  TIMESTAMPTZ  NOT NULL
);

-- primary_of is always false for a row in this table today - a primary stream lives in `record`
-- instead, never here; the column exists so a future phase can represent "this is the primary"
-- uniformly across record/activity_stream without a schema change, per the issue's own design.
CREATE TABLE activity_stream (
    id          BIGSERIAL    PRIMARY KEY,
    activity_id VARCHAR(40)  NOT NULL REFERENCES activity (id) ON DELETE CASCADE,
    field       VARCHAR(20)  NOT NULL,
    source_id   VARCHAR(40)  NOT NULL REFERENCES activity_source (id) ON DELETE CASCADE,
    t0          INTEGER      NOT NULL,
    -- Named stream_values, not values - VALUES is a reserved SQL keyword.
    stream_values SMALLINT[] NOT NULL,
    primary_of  BOOLEAN      NOT NULL DEFAULT FALSE,
    UNIQUE (activity_id, field, source_id)
);

CREATE INDEX idx_activity_source_activity ON activity_source (activity_id);
CREATE INDEX idx_activity_stream_activity_field ON activity_stream (activity_id, field);

-- Issue #499/#503 phase 1: bike-power equivalent of users.running_power_source - which source
-- (pedals/trainer) to prefer when attaching a second power stream decides a default primary.
-- 'any' (the default) keeps today's behaviour - no preference applied.
ALTER TABLE users ADD COLUMN bike_power_source VARCHAR(10) NOT NULL DEFAULT 'any' CHECK (bike_power_source IN ('pedals', 'trainer', 'any'));

-- Which source (pedals/trainer) this bike activity's current primary power stream was last
-- attached from, if any - see Activity.getBikePowerSource()'s own Javadoc.
ALTER TABLE activity ADD COLUMN bike_power_source VARCHAR(10) CHECK (bike_power_source IN ('pedals', 'trainer', 'any'));
