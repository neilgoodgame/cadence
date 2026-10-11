-- Reusable race courses - route, elevation profile and climbs, from a GPX upload or an
-- activity's GPS track. Matches the Python backend's courses app migration. Must land before
-- V72 (race.course_id references this table).
CREATE TABLE course (
    id                  VARCHAR(40)        PRIMARY KEY,
    athlete_id          VARCHAR(40)        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name                VARCHAR(200)       NOT NULL,
    location_name       VARCHAR(200)       NOT NULL DEFAULT '',
    start_lat           DOUBLE PRECISION,
    start_lng           DOUBLE PRECISION,
    distance_km         DOUBLE PRECISION,
    elevation_gain_m    INTEGER,
    elevation_loss_m    INTEGER,
    elevation_max_m     INTEGER,
    elevation_min_m     INTEGER,
    is_loop             BOOLEAN            NOT NULL DEFAULT false,
    source              VARCHAR(10)        NOT NULL CHECK (source IN ('gpx', 'activity')),
    source_activity_id  VARCHAR(40)        REFERENCES activity (id) ON DELETE SET NULL,
    gpx_file            VARCHAR(255)       NOT NULL DEFAULT '',
    gpx_filename        VARCHAR(255)       NOT NULL DEFAULT '',
    route_polyline      JSONB              NOT NULL DEFAULT '[]',
    elevation_profile   JSONB              NOT NULL DEFAULT '[]',
    climbs              JSONB              NOT NULL DEFAULT '[]',
    created_at          TIMESTAMPTZ        NOT NULL DEFAULT now()
);

CREATE INDEX idx_course_athlete ON course (athlete_id);
