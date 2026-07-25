-- Add 'row' as a valid sport value (indoor rowing, e.g. Concept2 erg)
ALTER TABLE activity DROP CONSTRAINT activity_sport_check;
ALTER TABLE activity ADD CONSTRAINT activity_sport_check
    CHECK (sport IN ('bike', 'run', 'swim', 'walk', 'row', 'multisport', 'transition'));
