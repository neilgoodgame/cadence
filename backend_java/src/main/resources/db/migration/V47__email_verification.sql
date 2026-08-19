-- Social signups (provider set on RegisterRequest) are marked verified immediately - the
-- provider already verified the address. Password signups start false and confirm via the
-- token flow below (see EmailVerificationService).
ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE email_verification_token (
    id              VARCHAR(40)  PRIMARY KEY,
    user_id         VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    hashed_secret   VARCHAR(64)  NOT NULL,
    created         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    used_at         TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_email_verification_token_hashed_secret ON email_verification_token (hashed_secret);
CREATE INDEX idx_email_verification_token_user ON email_verification_token (user_id);
