-- Single-level threading: a reply to a top-level comment, never a reply to a reply (enforced
-- in ActivityCommentService, not at the DB level) - see that service's Javadoc.
ALTER TABLE activity_comment
    ADD COLUMN parent_id VARCHAR(40) REFERENCES activity_comment (id) ON DELETE CASCADE;

CREATE INDEX idx_activity_comment_parent ON activity_comment (parent_id);
