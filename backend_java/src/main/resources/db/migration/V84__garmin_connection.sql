-- One Garmin Connect link per athlete. Today it holds a Personal API key (pull-on-request
-- sync); OAuth tokens for the business-tier program get their own columns when that lands.
CREATE TABLE garmin_connection (
    id                 VARCHAR(40)  PRIMARY KEY,
    owner_id           VARCHAR(40)  NOT NULL UNIQUE REFERENCES users (id) ON DELETE CASCADE,
    -- The Garmin account's email, which can differ from the Cadence login email.
    garmin_email       VARCHAR(320) NOT NULL,
    -- AES-256-GCM, "v1.<base64url(iv || ciphertext+tag)>" - never plaintext, never returned by the API.
    api_key_encrypted  TEXT         NOT NULL,
    -- Personal keys are issued for 30/90/180/365 days; Garmin doesn't expose it, so the athlete enters it.
    key_expires_on     DATE,
    last_synced_at     TIMESTAMPTZ,
    created            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
