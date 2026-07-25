ALTER TABLE best_effort DROP CONSTRAINT best_effort_kind_check;
ALTER TABLE best_effort ADD CONSTRAINT best_effort_kind_check
    CHECK (kind IN ('cycling_hr', 'cycling_power', 'running_hr', 'running_pace', 'running_power'));
