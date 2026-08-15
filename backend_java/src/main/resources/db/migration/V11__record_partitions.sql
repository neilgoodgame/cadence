-- Native RANGE partitioning on `ts`, monthly partitions covering 10 years back
-- through 6 months forward from whenever this migration actually runs (computed
-- at apply time via CURRENT_DATE, not baked to the date this file was written),
-- plus a DEFAULT catch-all partition as a safety net for anything outside that
-- range (very old imported data, clock skew) so an insert can never hard-fail for
-- lacking a matching partition. A scheduled task keeps rolling the forward end of
-- this range ahead over time - see PartitionMaintenanceService.
DO $$
DECLARE
    today_year  int := EXTRACT(YEAR FROM CURRENT_DATE)::int;
    today_month int := EXTRACT(MONTH FROM CURRENT_DATE)::int;
    start_total int := (today_year - 10) * 12 + (today_month - 1);
    end_total   int := today_year * 12 + (today_month - 1) + 7; -- +6 months, +1 for the loop's exclusive bound
    m           int;
    cur         date;
    nxt         date;
    part_name   text;
BEGIN
    FOR m IN start_total .. end_total - 1 LOOP
        cur := make_date(m / 12, m % 12 + 1, 1);
        nxt := make_date((m + 1) / 12, (m + 1) % 12 + 1, 1);
        part_name := 'record_p' || to_char(cur, 'YYYYMM');
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF record FOR VALUES FROM (%L) TO (%L);',
            part_name,
            to_char(cur, 'YYYY-MM-DD') || ' 00:00:00+00',
            to_char(nxt, 'YYYY-MM-DD') || ' 00:00:00+00'
        );
    END LOOP;
END $$;

CREATE TABLE record_default PARTITION OF record DEFAULT;
