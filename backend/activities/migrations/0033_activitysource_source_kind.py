from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ("activities", "0032_activitysource_running_power_kind"),
    ]

    operations = [
        # Issue #503 (phase 3): generalizes running_power_kind into a reusable per-field tag
        # column - RenameField does a real ALTER TABLE ... RENAME COLUMN, not drop+add, so
        # existing phase-2 tagged values (native/stryd) survive. The separate AlterField drops
        # the narrow choices= (the valid value set now depends on the owning row's sensor_kind),
        # consistent with the Java side's own V102 migration dropping its CHECK constraint.
        migrations.RenameField(model_name="activitysource", old_name="running_power_kind", new_name="source_kind"),
        migrations.AlterField(
            model_name="activitysource",
            name="source_kind",
            field=models.CharField(max_length=10, blank=True, default=""),
        ),
        migrations.AddField(
            model_name="activity",
            name="hr_source",
            field=models.CharField(
                max_length=10, blank=True, default="",
                choices=[("strap", "Strap"), ("wrist", "Wrist")],
            ),
        ),
        migrations.AddField(
            model_name="activity",
            name="cadence_source",
            field=models.CharField(
                max_length=10, blank=True, default="",
                choices=[("pedals", "Pedals"), ("trainer", "Trainer"), ("watch", "Watch")],
            ),
        ),
    ]
