-- Issue #498: Zwift FITs carry a virtual GPS track, so every Zwift ride was previously
-- stored as outdoor (has_gps -> OUTDOOR regardless of source). The parser fix (same
-- commit) only affects newly-uploaded files; this backfills existing rows.
-- device is "Zwift" alone or "Zwift <product>" (e.g. "Zwift X") - see
-- FitFileParser#deviceName - so match on the prefix, not an exact value.
update activity
set environment = 'indoor'
where device like 'Zwift%'
  and environment <> 'indoor';
