ALTER TABLE users ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT FALSE;

-- Catalog-change audit trail for the Admin screen - scoped to shoe-catalog add/remove
-- actions only, not a general admin-action log (user/admin-flag toggles and coach-grant
-- revokes don't write here).
CREATE TABLE catalog_audit_log_entry (
    id           VARCHAR(40)  PRIMARY KEY,
    description  VARCHAR(300) NOT NULL,
    action       VARCHAR(10)  NOT NULL CHECK (action IN ('added', 'removed')),
    by_id        VARCHAR(40)  REFERENCES users (id) ON DELETE SET NULL,
    created      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_catalog_audit_log_created ON catalog_audit_log_entry (created DESC);
