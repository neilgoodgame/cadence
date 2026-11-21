-- Issue #542: the standalone detach_source backfill creates an activity_merge row for a
-- pre-existing orphaned attach, but the real offset used at the original (pre-tracking) attach
-- time was never recorded anywhere - there's no honest value to put here. null=True rather than
-- a fabricated 0, same reasoning V106 already applied to null_policy.
ALTER TABLE activity_merge ALTER COLUMN offset_seconds DROP NOT NULL;
