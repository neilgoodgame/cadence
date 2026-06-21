CREATE EXTENSION IF NOT EXISTS timescaledb;

CREATE TABLE users (
    id                  VARCHAR(40)  PRIMARY KEY,
    email               VARCHAR(255) NOT NULL,
    password            VARCHAR(255),
    name                VARCHAR(150) NOT NULL,
    handle              VARCHAR(50),
    age                 INTEGER,
    weight_kg           DOUBLE PRECISION,
    ftp                 INTEGER,
    critical_run_power  INTEGER,
    threshold_pace      VARCHAR(10)  NOT NULL DEFAULT '',
    lthr                INTEGER,
    max_hr              INTEGER,
    is_coach            BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active           BOOLEAN      NOT NULL DEFAULT TRUE,
    date_joined         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT unique_user_email UNIQUE (email),
    CONSTRAINT unique_user_handle UNIQUE (handle)
);
