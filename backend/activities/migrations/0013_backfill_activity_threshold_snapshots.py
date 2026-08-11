# Seeds ftp_snapshot/critical_run_power_snapshot/threshold_pace_snapshot on every existing
# activity from the athlete's CURRENT profile values - there's no record of what an athlete's
# thresholds actually were back when an old activity happened, so this is only accurate going
# forward from here. Bulk .update(Subquery(...)) rather than a per-row Python loop: cheap even
# against a multi-thousand-activity account (see dataexport's streaming design for why that
# scale is a real concern in this codebase). Plain F("athlete__ftp") can't cross the relation
# in an .update() ("Joined field references are not permitted in this query" - Django disallows
# joins in UPDATE targets), so this uses a correlated subquery instead.
from django.db import migrations
from django.db.models import OuterRef, Subquery


def backfill_snapshots(apps, schema_editor):
    Activity = apps.get_model("activities", "Activity")
    User = apps.get_model("accounts", "User")
    Activity.objects.filter(sport="bike").update(
        ftp_snapshot=Subquery(User.objects.filter(pk=OuterRef("athlete_id")).values("ftp")[:1])
    )
    Activity.objects.filter(sport="run").update(
        critical_run_power_snapshot=Subquery(
            User.objects.filter(pk=OuterRef("athlete_id")).values("critical_run_power")[:1]
        ),
        threshold_pace_snapshot=Subquery(
            User.objects.filter(pk=OuterRef("athlete_id")).values("threshold_pace")[:1]
        ),
    )


def unbackfill_snapshots(apps, schema_editor):
    # No-op: reversing would just null the fields out again, which the AddField reversal
    # in 0012 already does implicitly by dropping the columns.
    pass


class Migration(migrations.Migration):

    dependencies = [
        ("activities", "0012_activity_threshold_snapshots"),
        ("accounts", "0007_user_copy_matched_workout_tags"),
    ]

    operations = [
        migrations.RunPython(backfill_snapshots, unbackfill_snapshots),
    ]
