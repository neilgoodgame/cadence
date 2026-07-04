# The shoe catalog is read-only reference data shared across every athlete (not athlete-owned -
# see ShoeModel/ShoeModelVersion's docstrings) and ships with nothing seeded, so the "add shoes"
# UI's catalog search had no real shoes to find for any athlete. Seeding a starter catalog of
# popular running shoes across major brands so that flow actually works out of the box.
import secrets

from django.db import migrations

_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"


# Replicated from core/models.py — migrations must be self-contained; can't import live app code.
def _generate_id(prefix: str, length: int = 14) -> str:
    suffix = "".join(secrets.choice(_ALPHABET) for _ in range(length))
    return f"{prefix}_{suffix}"

CATALOG = [
    ("Nike", "Vaporfly", ["3"]),
    ("Nike", "Alphafly", ["3"]),
    ("Nike", "Pegasus", ["41"]),
    ("Nike", "Invincible", ["3"]),
    ("Hoka", "Clifton", ["9"]),
    ("Hoka", "Mach", ["6"]),
    ("Hoka", "Bondi", ["8"]),
    ("Hoka", "Rincon", ["3"]),
    ("Brooks", "Ghost", ["16"]),
    ("Brooks", "Glycerin", ["21"]),
    ("Brooks", "Hyperion Tempo", ["1"]),
    ("Saucony", "Endorphin Speed", ["4"]),
    ("Saucony", "Ride", ["17"]),
    ("Saucony", "Kinvara", ["14"]),
    ("Adidas", "Adizero Adios Pro", ["3"]),
    ("Adidas", "Boston", ["12"]),
    ("Adidas", "Ultraboost Light", ["1"]),
    ("ASICS", "Gel-Nimbus", ["26"]),
    ("ASICS", "Gel-Kayano", ["31"]),
    ("ASICS", "Magic Speed", ["3"]),
    ("New Balance", "FuelCell SuperComp Elite", ["v4"]),
    ("New Balance", "1080", ["v13"]),
    ("New Balance", "Rebel", ["v4"]),
    ("On", "Cloudmonster", ["2"]),
    ("On", "Cloudboom Echo", ["3"]),
]


def seed_catalog(apps, schema_editor):
    ShoeModel = apps.get_model("gear", "ShoeModel")
    ShoeModelVersion = apps.get_model("gear", "ShoeModelVersion")
    for manufacturer, model, versions in CATALOG:
        shoe_model = ShoeModel.objects.create(
            id=_generate_id("sm"), manufacturer=manufacturer, model=model
        )
        for version in versions:
            ShoeModelVersion.objects.create(
                id=_generate_id("smv"), shoe_model=shoe_model, version=version
            )


def unseed_catalog(apps, schema_editor):
    ShoeModel = apps.get_model("gear", "ShoeModel")
    ShoeModel.objects.filter(manufacturer__in={manufacturer for manufacturer, _, _ in CATALOG}).delete()


class Migration(migrations.Migration):
    dependencies = [
        ("gear", "0001_initial"),
    ]

    operations = [
        migrations.RunPython(seed_catalog, unseed_catalog),
    ]
