from django.conf import settings
from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('activities', '0009_sport_row'),
        migrations.swappable_dependency(settings.AUTH_USER_MODEL),
    ]

    operations = [
        migrations.RemoveConstraint(
            model_name='besteffort',
            name='unique_athlete_kind_window',
        ),
        migrations.AddConstraint(
            model_name='besteffort',
            constraint=models.UniqueConstraint(fields=['athlete', 'kind', 'window', 'activity'], name='unique_athlete_kind_window_activity'),
        ),
        migrations.AlterModelOptions(
            name='besteffort',
            options={'ordering': ['kind', 'window', '-value']},
        ),
    ]
