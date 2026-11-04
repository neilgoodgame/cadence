# Issue #498: Zwift FITs carry a virtual GPS track, so every Zwift ride was previously
# stored as outdoor (has_gps -> outdoor regardless of source). The parser fix (same
# commit) only affects newly-ingested files; this backfills existing rows. device is
# "Zwift" alone or "Zwift <product>" (e.g. "Zwift X") - see uploads/parsers/fit.py's
# _device_name - so match on the prefix, not an exact value.
from django.db import migrations

BACKFILL_SQL = """
    UPDATE activities_activity
    SET environment = 'indoor'
    WHERE device LIKE 'Zwift%' AND environment <> 'indoor';
"""

# Not reversible - the original (incorrect) environment value isn't recoverable.
UNBACKFILL_SQL = migrations.RunSQL.noop


class Migration(migrations.Migration):
    dependencies = [
        ("activities", "0023_activity_elevation"),
    ]

    operations = [
        migrations.RunSQL(BACKFILL_SQL, UNBACKFILL_SQL),
    ]
