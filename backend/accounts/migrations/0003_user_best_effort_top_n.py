from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('accounts', '0002_user_handle_personalaccesstoken_userrelationship_and_more'),
    ]

    operations = [
        migrations.AddField(
            model_name='user',
            name='best_effort_top_n',
            field=models.PositiveSmallIntegerField(default=10),
        ),
    ]
