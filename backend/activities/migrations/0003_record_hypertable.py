from django.db import migrations


class Migration(migrations.Migration):
    """Converts activities_record into a TimescaleDB hypertable partitioned on
    `ts`. Timescale requires every unique/PK constraint on a hypertable to
    include the partitioning column — the surrogate `id` PK Django generated
    doesn't, so it's dropped in favor of a plain (non-unique) index, leaving
    (activity, ts) — added in the previous migration and already inclusive of
    `ts` — as the sole uniqueness guarantee.
    """

    dependencies = [
        ("activities", "0002_besteffort_durationcurve_record_and_more"),
    ]

    operations = [
        migrations.RunSQL(
            sql=[
                "CREATE EXTENSION IF NOT EXISTS timescaledb;",
                "ALTER TABLE activities_record DROP CONSTRAINT activities_record_pkey;",
                "CREATE INDEX activities_record_id_idx ON activities_record (id);",
                "SELECT create_hypertable('activities_record', 'ts', "
                "chunk_time_interval => INTERVAL '1 day', migrate_data => TRUE);",
            ],
            reverse_sql=[
                "SELECT 1;",
            ],
        ),
    ]
