-- kind: how a row's value was determined, previously indistinguishable between a manual edit
-- and a decayed row (issue #480's decay core, #485) - both have no source_activity_id. Every
-- existing row predates decay, so the backfill below is unambiguous: a row with no source
-- activity is manual, otherwise measured.
ALTER TABLE threshold_history
    ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'measured'
        CHECK (kind IN ('measured', 'manual', 'decayed'));
UPDATE threshold_history SET kind = 'manual' WHERE source_activity_id IS NULL;

CREATE INDEX idx_threshold_history_athlete_field_kind_effective_from
    ON threshold_history (athlete_id, field, kind, effective_from);
