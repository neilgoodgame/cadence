-- Issue #501 (phase 1, part 5): the target's own value for each requested *activity-level*
-- field, captured immediately before the merge overwrote it - see Merge.previousActivityValues'
-- own Javadoc for why undo needs this rather than re-deriving it.
ALTER TABLE activity_merge ADD COLUMN previous_activity_values JSONB NOT NULL DEFAULT '{}'::jsonb;
