-- Stryd footpod (air_temp, humidity) and CORE body-temperature sensor (core_temp, skin_temp,
-- heat_strain) developer fields - FIT-only, no GPX/TCX equivalent. Matches the Python backend's
-- 0004_activity_avg_air_temp_activity_avg_humidity_and_more migration.
ALTER TABLE record
    ADD COLUMN air_temp    DOUBLE PRECISION,
    ADD COLUMN humidity    INTEGER,
    ADD COLUMN core_temp   DOUBLE PRECISION,
    ADD COLUMN skin_temp   DOUBLE PRECISION,
    ADD COLUMN heat_strain DOUBLE PRECISION;

ALTER TABLE activity
    ADD COLUMN avg_air_temp DOUBLE PRECISION,
    ADD COLUMN avg_humidity INTEGER;
