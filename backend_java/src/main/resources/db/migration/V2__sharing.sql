CREATE TABLE user_relationship (
    id          VARCHAR(40)  PRIMARY KEY,
    owner_id    VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    grantee_id  VARCHAR(40)  NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role        VARCHAR(10)  NOT NULL CHECK (role IN ('viewer', 'coach')),
    status      VARCHAR(10)  NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'active')),
    created     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT unique_owner_grantee UNIQUE (owner_id, grantee_id)
);

CREATE INDEX idx_user_relationship_grantee ON user_relationship (grantee_id, status);
CREATE INDEX idx_user_relationship_owner ON user_relationship (owner_id, status);
