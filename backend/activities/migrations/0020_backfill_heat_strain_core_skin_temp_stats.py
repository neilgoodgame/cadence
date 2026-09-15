# Backfills avg/max heat_strain/core_temp/skin_temp on every existing activity from its own
# record stream. Raw SQL grouped-aggregate UPDATE, not a per-activity Python loop that loads
# each activity's records - that pattern (StreamsView/StreamService's old records[::step]
# decimation) already took the Java backend OOM once this session on a long activity's full
# record set; a per-activity Python backfill loop across every activity ever ingested would be
# the same mistake at a much larger scale. Postgres does the aggregation itself, one set-based
# statement, memory cost independent of history size.
from django.db import migrations

BACKFILL_SQL = """
    UPDATE activities_activity a
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
        FROM activities_record
        WHERE heat_strain IS NOT NULL OR core_temp IS NOT NULL OR skin_temp IS NOT NULL
        GROUP BY activity_id
    ) sub
    WHERE a.id = sub.activity_id;
"""

UNBACKFILL_SQL = """
    UPDATE activities_activity
    SET avg_heat_strain = NULL, max_heat_strain = NULL,
        avg_core_temp = NULL, max_core_temp = NULL,
        avg_skin_temp = NULL, max_skin_temp = NULL;
"""


class Migration(migrations.Migration):

    dependencies = [
        ("activities", "0019_activity_heat_strain_core_skin_temp_stats"),
    ]

    operations = [
        migrations.RunSQL(BACKFILL_SQL, UNBACKFILL_SQL),
    ]
