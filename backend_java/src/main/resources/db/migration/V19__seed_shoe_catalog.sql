-- The shoe catalog ships empty; seed it so the "Add shoes" search flow works out of the box.
-- IDs use the same sm_/smv_ prefix convention as the Python backend's PrefixedIDModel.
-- We generate them via md5(random()) since Flyway migrations run plain SQL with no app code.

DO $$
DECLARE
    sm_id VARCHAR(40);
BEGIN
    -- Nike
    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'Nike', 'Vaporfly');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '3');

    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'Nike', 'Alphafly');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '3');

    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'Nike', 'Pegasus');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '41');

    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'Nike', 'Invincible');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '3');

    -- Hoka
    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'Hoka', 'Clifton');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '9');

    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'Hoka', 'Mach');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '6');

    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'Hoka', 'Bondi');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '8');

    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'Hoka', 'Rincon');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '3');

    -- Brooks
    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'Brooks', 'Ghost');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '16');

    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'Brooks', 'Glycerin');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '21');

    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'Brooks', 'Hyperion Tempo');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '1');

    -- Saucony
    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'Saucony', 'Endorphin Speed');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '4');

    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'Saucony', 'Ride');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '17');

    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'Saucony', 'Kinvara');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '14');

    -- Adidas
    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'Adidas', 'Adizero Adios Pro');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '3');

    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'Adidas', 'Boston');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '12');

    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'Adidas', 'Ultraboost Light');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '1');

    -- ASICS
    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'ASICS', 'Gel-Nimbus');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '26');

    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'ASICS', 'Gel-Kayano');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '31');

    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'ASICS', 'Magic Speed');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '3');

    -- New Balance
    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'New Balance', 'FuelCell SuperComp Elite');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, 'v4');

    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'New Balance', '1080');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, 'v13');

    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'New Balance', 'Rebel');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, 'v4');

    -- On
    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'On', 'Cloudmonster');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '2');

    sm_id := 'sm_' || substr(md5(random()::text), 1, 14); INSERT INTO shoe_model (id, manufacturer, model) VALUES (sm_id, 'On', 'Cloudboom Echo');
    INSERT INTO shoe_model_version (id, shoe_model_id, version) VALUES ('smv_' || substr(md5(random()::text), 1, 14), sm_id, '3');
END $$;
