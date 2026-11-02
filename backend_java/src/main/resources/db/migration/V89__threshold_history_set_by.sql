ALTER TABLE threshold_history
    ADD COLUMN set_by_user_id VARCHAR(40) REFERENCES users (id) ON DELETE SET NULL;
