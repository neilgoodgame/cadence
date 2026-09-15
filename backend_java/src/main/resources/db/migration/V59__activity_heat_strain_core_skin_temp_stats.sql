-- Avg/max heat_strain/core_temp/skin_temp on Activity, same "computed once from the record
-- stream" shape as avg_air_temp/avg_humidity (V17) - added so heat-strain/core-temp questions
-- (e.g. "which sessions had the highest heat strain in the last 3 months") can be answered by
-- one CQL-filtered query instead of an MCP client fetching every activity's full stream and
-- computing this itself. Matches the Python backend's
-- 0019_activity_heat_strain_core_skin_temp_stats/0020_backfill_heat_strain_core_skin_temp_stats
-- migrations.
ALTER TABLE activity
    ADD COLUMN avg_heat_strain DOUBLE PRECISION,
    ADD COLUMN max_heat_strain DOUBLE PRECISION,
    ADD COLUMN avg_core_temp   DOUBLE PRECISION,
    ADD COLUMN max_core_temp   DOUBLE PRECISION,
    ADD COLUMN avg_skin_temp   DOUBLE PRECISION,
    ADD COLUMN max_skin_temp   DOUBLE PRECISION;

-- Backfilled here (not via DerivedStatsRecomputeService against every existing activity) for the
-- same reason V59's Python counterpart uses a raw grouped-aggregate UPDATE: a per-activity loop
-- that loads each activity's full record stream into memory is the exact pattern that took this
-- backend OOM once already this session (see the stream-decimation fix) - at the scale of every
-- activity ever ingested, that would be the same mistake far larger. Postgres does the
-- aggregation itself, one set-based statement, memory cost independent of history size.
UPDATE activity a
SET avg_heat_strain = sub.avg_heat_strain,
    max_heat_strain = sub.max_heat_strain,
    avg_core_temp = sub.avg_core_temp,
    max_core_temp = sub.max_core_temp,
    avg_skin_temp = sub.avg_skin_temp,
    max_skin_temp = sub.max_skin_temp
FROM (
    SELECT
        activity_id,
        round(avg(heat_strain)::numeric, 1)::float8 AS avg_heat_strain,
        round(max(heat_strain)::numeric, 1)::float8 AS max_heat_strain,
        round(avg(core_temp)::numeric, 1)::float8 AS avg_core_temp,
        round(max(core_temp)::numeric, 1)::float8 AS max_core_temp,
        round(avg(skin_temp)::numeric, 1)::float8 AS avg_skin_temp,
        round(max(skin_temp)::numeric, 1)::float8 AS max_skin_temp
    FROM record
    WHERE heat_strain IS NOT NULL OR core_temp IS NOT NULL OR skin_temp IS NOT NULL
    GROUP BY activity_id
) sub
WHERE a.id = sub.activity_id;
