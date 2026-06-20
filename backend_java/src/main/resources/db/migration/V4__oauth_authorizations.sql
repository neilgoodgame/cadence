-- Durable storage for issued OAuth2 authorizations (Spring Authorization Server's
-- OAuth2Authorization, persisted through a custom JPA-backed OAuth2AuthorizationService
-- rather than the framework's own JDBC schema - see security.oauth.JpaOAuth2AuthorizationService).
-- Only authorization_code + refresh_token grants are used, so device-code/OIDC-id-token
-- columns from the framework's reference schema are intentionally omitted.
CREATE TABLE oauth_authorization (
    id                              VARCHAR(40)   PRIMARY KEY,
    registered_client_id            VARCHAR(100)  NOT NULL,
    principal_name                  VARCHAR(100)  NOT NULL,
    authorization_grant_type        VARCHAR(50)   NOT NULL,
    authorized_scopes               VARCHAR(1000) NOT NULL DEFAULT '',
    attributes                      TEXT,
    state                           VARCHAR(500),

    authorization_code_value        TEXT,
    authorization_code_issued_at    TIMESTAMPTZ,
    authorization_code_expires_at   TIMESTAMPTZ,
    authorization_code_metadata     TEXT,

    access_token_value              TEXT,
    access_token_issued_at          TIMESTAMPTZ,
    access_token_expires_at         TIMESTAMPTZ,
    access_token_metadata           TEXT,
    access_token_type               VARCHAR(50),
    access_token_scopes             VARCHAR(1000),

    refresh_token_value             TEXT,
    refresh_token_issued_at         TIMESTAMPTZ,
    refresh_token_expires_at        TIMESTAMPTZ,
    refresh_token_metadata          TEXT
);

CREATE INDEX idx_oauth_authorization_access_token ON oauth_authorization (access_token_value);
CREATE INDEX idx_oauth_authorization_refresh_token ON oauth_authorization (refresh_token_value);
CREATE INDEX idx_oauth_authorization_code ON oauth_authorization (authorization_code_value);
CREATE INDEX idx_oauth_authorization_state ON oauth_authorization (state);
