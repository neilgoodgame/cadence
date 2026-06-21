CREATE TABLE bike (
    id          VARCHAR(40)  PRIMARY KEY,
    athlete_id  VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        VARCHAR(150) NOT NULL,
    kind        VARCHAR(10)  CHECK (kind IN ('road', 'indoor', 'gravel', 'tt')),
    groupset    VARCHAR(150) NOT NULL DEFAULT '',
    distance_km INTEGER      NOT NULL DEFAULT 0,
    hours       DOUBLE PRECISION NOT NULL DEFAULT 0,
    rides       INTEGER      NOT NULL DEFAULT 0
);

CREATE INDEX idx_bike_athlete ON bike (athlete_id);

CREATE TABLE component (
    id        VARCHAR(40)  PRIMARY KEY,
    bike_id   VARCHAR(40)  NOT NULL REFERENCES bike (id) ON DELETE CASCADE,
    name      VARCHAR(150) NOT NULL,
    km        INTEGER      NOT NULL DEFAULT 0,
    limit_km  INTEGER      NOT NULL,
    model     VARCHAR(150) NOT NULL DEFAULT ''
);

CREATE INDEX idx_component_bike ON component (bike_id);

CREATE TABLE service_record (
    id            VARCHAR(40)  PRIMARY KEY,
    component_id  VARCHAR(40)  NOT NULL REFERENCES component (id) ON DELETE CASCADE,
    action        VARCHAR(10)  CHECK (action IS NULL OR action IN ('replaced', 'cleaned', 'inspected', 'adjusted')),
    reset         BOOLEAN      NOT NULL DEFAULT TRUE,
    note          VARCHAR(500) NOT NULL DEFAULT '',
    date          DATE         NOT NULL
);

CREATE INDEX idx_service_record_component ON service_record (component_id);

CREATE TABLE shoe (
    id                       VARCHAR(40)  PRIMARY KEY,
    athlete_id               VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    shoe_model_version_id    VARCHAR(40)  NOT NULL REFERENCES shoe_model_version (id) ON DELETE RESTRICT,
    colourway                VARCHAR(150) NOT NULL DEFAULT '',
    name                     VARCHAR(200) NOT NULL,
    image                    VARCHAR(2048),
    role                     VARCHAR(150) NOT NULL DEFAULT '',
    km                       INTEGER      NOT NULL DEFAULT 0,
    limit_km                 INTEGER      NOT NULL DEFAULT 0,
    since                    DATE         NOT NULL DEFAULT CURRENT_DATE,
    retired                  BOOLEAN      NOT NULL DEFAULT FALSE,

    CONSTRAINT unique_athlete_shoe_name UNIQUE (athlete_id, name)
);

CREATE INDEX idx_shoe_athlete ON shoe (athlete_id);
