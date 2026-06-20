-- No surrogate id: (activity_id, ts) is already the natural unique key the data dictionary
-- requires, it already includes the partitioning column TimescaleDB will need, and an
-- immutable 1 Hz fact table has no real use for one anyway - nothing ever fetches a Record
-- by its own id, only by activity_id + time range.
CREATE TABLE record (
    activity_id   VARCHAR(40)       NOT NULL REFERENCES activity (id) ON DELETE CASCADE,
    ts            TIMESTAMPTZ       NOT NULL,
    t             INTEGER           NOT NULL,
    power         INTEGER,
    heartrate     INTEGER,
    cadence       INTEGER,
    altitude      DOUBLE PRECISION,
    lat           DOUBLE PRECISION,
    lng           DOUBLE PRECISION,
    speed         DOUBLE PRECISION,
    distance_km   DOUBLE PRECISION,

    PRIMARY KEY (activity_id, ts)
);
