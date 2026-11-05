# Issue #498: moving_time is really just the record stream's own span (last Record.t),
# which understates a ride's true wall-clock duration whenever the device auto-paused
# recording. elapsed_seconds, populated at ingest from the FIT session's own
# total_elapsed_time (includes pauses), is the better signal the overlap-detection query
# needs. Existing rows have no such session data to recover - moving_time is already the
# best approximation available for them, same as the query-time fallback new code uses.
from django.db import migrations

BACKFILL_SQL = """
    UPDATE activities_activity
    SET elapsed_seconds = moving_time
    WHERE elapsed_seconds IS NULL;
"""

UNBACKFILL_SQL = """
    UPDATE activities_activity SET elapsed_seconds = NULL;
"""


class Migration(migrations.Migration):
    dependencies = [
        ("activities", "0025_activity_elapsed_seconds"),
    ]

    operations = [
        migrations.RunSQL(BACKFILL_SQL, UNBACKFILL_SQL),
    ]
