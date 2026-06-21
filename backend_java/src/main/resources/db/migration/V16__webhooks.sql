CREATE TABLE webhook (
    id        VARCHAR(40)  PRIMARY KEY,
    owner_id  VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    url       VARCHAR(2048) NOT NULL,
    status    VARCHAR(10)  NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
    events    JSONB        NOT NULL DEFAULT '[]',
    -- HMAC-signs every delivery, not verified once like a password - stored as plaintext
    -- (never hashed) so a delivery attempt can re-sign on each retry.
    secret    VARCHAR(64)  NOT NULL,
    created   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhook_owner ON webhook (owner_id);

-- Internal delivery-attempt bookkeeping, not part of the public API surface.
CREATE TABLE webhook_delivery (
    id          BIGSERIAL    PRIMARY KEY,
    webhook_id  VARCHAR(40)  NOT NULL REFERENCES webhook (id) ON DELETE CASCADE,
    event       VARCHAR(50)  NOT NULL,
    payload     JSONB        NOT NULL,
    status      VARCHAR(10)  NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'succeeded', 'failed')),
    attempts    INTEGER      NOT NULL DEFAULT 0,
    last_error  TEXT         NOT NULL DEFAULT '',
    created     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_webhook_delivery_webhook ON webhook_delivery (webhook_id);
CREATE INDEX idx_webhook_delivery_pending ON webhook_delivery (status) WHERE status = 'pending';
