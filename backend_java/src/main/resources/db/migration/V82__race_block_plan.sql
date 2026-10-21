-- Per-race training-block length and explicit Base/Build/Peak/Taper phase lengths (A races
-- only). All null by default - falls back to RaceBlockService.resolveBlockWeeks's distance-
-- bucket default, then the athlete's flat race_block_weeks, then the proportional phases()
-- split, exactly as before these columns existed.
ALTER TABLE race ADD COLUMN block_weeks_requested INTEGER;
ALTER TABLE race ADD COLUMN base_weeks_requested INTEGER;
ALTER TABLE race ADD COLUMN build_weeks_requested INTEGER;
ALTER TABLE race ADD COLUMN peak_weeks_requested INTEGER;
ALTER TABLE race ADD COLUMN taper_weeks_requested INTEGER;
