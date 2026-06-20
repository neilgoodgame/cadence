CREATE TABLE zone_set (
    id          BIGSERIAL PRIMARY KEY,
    athlete_id  VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type        VARCHAR(20)  NOT NULL CHECK (type IN ('heart_rate', 'bike_power', 'run_power', 'pace')),
    zones       JSONB        NOT NULL,

    CONSTRAINT unique_athlete_zone_type UNIQUE (athlete_id, type)
);
