-- Races feature preferences (see com.cadence.api.races.RaceBlockService). Matches the Python
-- backend's accounts migration adding the same two fields exactly.
ALTER TABLE users
    ADD COLUMN race_block_weeks       INTEGER NOT NULL DEFAULT 16,
    ADD COLUMN race_key_session_cap   INTEGER NOT NULL DEFAULT 10;
