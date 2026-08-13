-- A manually-entered threshold (see ThresholdHistoryService.recordManualValue) has no source
-- activity - the athlete declared it directly via their profile.
ALTER TABLE threshold_history ALTER COLUMN source_activity_id DROP NOT NULL;
