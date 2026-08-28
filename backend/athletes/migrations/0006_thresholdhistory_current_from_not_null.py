from django.db import migrations, models


def backfill_current_from(apps, schema_editor):
    # Safe, non-regressing default for existing rows: current_from = effective_from is exactly
    # today's (buggy) behavior, so this changes nothing for anyone until they explicitly re-run
    # the "recompute history from oldest" tool, which repopulates current_from correctly for any
    # row that only became current later via an earlier entry aging out - see
    # threshold_history.py's module docstring and ThresholdHistory.current_from's own docstring.
    ThresholdHistory = apps.get_model('athletes', 'ThresholdHistory')
    ThresholdHistory.objects.filter(current_from__isnull=True).update(
        current_from=models.F('effective_from')
    )


class Migration(migrations.Migration):

    dependencies = [
        ('athletes', '0005_thresholdhistory_current_from'),
    ]

    operations = [
        migrations.RunPython(backfill_current_from, migrations.RunPython.noop),
        migrations.AlterField(
            model_name='thresholdhistory',
            name='current_from',
            field=models.DateField(),
        ),
    ]
