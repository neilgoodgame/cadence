from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('athletes', '0004_besteffortrecomputejob'),
    ]

    operations = [
        migrations.AddField(
            model_name='thresholdhistory',
            name='current_from',
            field=models.DateField(null=True),
        ),
        migrations.AddIndex(
            model_name='thresholdhistory',
            index=models.Index(fields=['athlete', 'field', 'current_from'], name='athletes_th_athlete_b2f439_idx'),
        ),
    ]
