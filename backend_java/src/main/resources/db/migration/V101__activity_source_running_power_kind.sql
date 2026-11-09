-- Issue #503 (phase 2): running's power-kind tag for an ActivitySource row - mirrors
-- Activity.power_source's native/stryd distinction, but per stored source rather than per
-- activity, since a run's two power candidates share the SAME device string (no
-- classify()-by-device heuristic is possible here, unlike bike's pedals/trainer sources - see
-- ActivitySourceService#classify's own Javadoc). Null for every bike-sourced row and any future
-- non-power sensor_kind row; set only when sensor_kind = 'power' and the owning activity's sport
-- is 'run'.
ALTER TABLE activity_source ADD COLUMN running_power_kind VARCHAR(10) CHECK (running_power_kind IN ('native', 'stryd'));
