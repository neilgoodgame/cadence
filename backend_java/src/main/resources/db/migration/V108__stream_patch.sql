-- Issue #540 (phase 6): one applied repair - replaces activity's primary stream for field, at
-- given segments, with from_source's own stored reading there. Always patches the *primary*
-- stream (never a stored activity_source row's own stream) - see StreamPatch's own Javadoc.
-- from_source_id has no ON DELETE clause here (Postgres default: block the delete) - a
-- deliberate difference from activity_merge's SET_NULL FKs: a stream_patch without knowing
-- which source it copied from can't be meaningfully unpatched, so block the delete instead of
-- letting the audit row go silently unrestorable.
CREATE TABLE stream_patch (
    id              VARCHAR(40)  PRIMARY KEY,
    activity_id     VARCHAR(40)  NOT NULL REFERENCES activity (id) ON DELETE CASCADE,
    field           VARCHAR(20)  NOT NULL,
    from_source_id  VARCHAR(40)  NOT NULL REFERENCES activity_source (id),
    detected_kind   VARCHAR(20),
    segments        JSONB        NOT NULL,
    previous_values JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    undone_at       TIMESTAMPTZ
);

CREATE INDEX idx_stream_patch_activity_field ON stream_patch (activity_id, field);
CREATE INDEX idx_stream_patch_from_source ON stream_patch (from_source_id);
