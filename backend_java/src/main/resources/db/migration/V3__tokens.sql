CREATE TABLE personal_access_token (
    id              VARCHAR(40)  PRIMARY KEY,
    user_id         VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name            VARCHAR(150) NOT NULL,
    prefix          VARCHAR(20)  NOT NULL,
    hashed_secret   VARCHAR(64)  NOT NULL,
    scopes          JSONB        NOT NULL DEFAULT '[]',
    created         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      DATE,
    last_used       DATE
);

CREATE INDEX idx_personal_access_token_prefix ON personal_access_token (prefix);
CREATE INDEX idx_personal_access_token_user ON personal_access_token (user_id);
