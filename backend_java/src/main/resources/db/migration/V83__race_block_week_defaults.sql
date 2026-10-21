-- Per-distance-bucket training-block-length defaults (RaceBlockService.resolveBlockWeeks),
-- mirroring backend's accounts migration 0021. Seeded with static defaults for every bucket
-- except marathon, which is backfilled from each existing user's own race_block_weeks below -
-- marathon is by far the most common A-race distance, so this preserves today's actual
-- behavior for existing accounts rather than silently changing it to the new static seed (16)
-- on this migration's own account.
ALTER TABLE users ADD COLUMN race_block_week_defaults JSONB NOT NULL
    DEFAULT '{"5k": 8, "10k": 10, "10mi": 12, "half": 12, "marathon": 16, "ultra": 20}';

UPDATE users SET race_block_week_defaults = jsonb_set(race_block_week_defaults, '{marathon}', to_jsonb(race_block_weeks));
