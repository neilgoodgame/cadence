ALTER TABLE activity ADD COLUMN ftp_snapshot INTEGER;
ALTER TABLE activity ADD COLUMN critical_run_power_snapshot INTEGER;
ALTER TABLE activity ADD COLUMN threshold_pace_snapshot VARCHAR(10) NOT NULL DEFAULT '';
ALTER TABLE activity ADD COLUMN suggested_ftp INTEGER;
ALTER TABLE activity ADD COLUMN suggested_critical_run_power INTEGER;
ALTER TABLE activity ADD COLUMN suggested_threshold_pace VARCHAR(10) NOT NULL DEFAULT '';

-- Seeds existing activities from the athlete's CURRENT threshold values - there's no record of
-- what an athlete's thresholds actually were back when an old activity happened, so this is
-- only accurate going forward from here (mirrors the Django backend's equivalent migration).
UPDATE activity a SET ftp_snapshot = u.ftp
    FROM users u WHERE a.athlete_id = u.id AND a.sport = 'bike';
UPDATE activity a SET critical_run_power_snapshot = u.critical_run_power, threshold_pace_snapshot = u.threshold_pace
    FROM users u WHERE a.athlete_id = u.id AND a.sport = 'run';
