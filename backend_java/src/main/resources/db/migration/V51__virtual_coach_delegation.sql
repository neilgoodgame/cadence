ALTER TABLE users ADD COLUMN is_virtual BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE personal_access_token
    ADD COLUMN delegated_athlete_id VARCHAR(40) REFERENCES users(id) ON DELETE CASCADE;
