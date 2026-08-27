from django.db import models

from accounts.models import User
from core.models import PrefixedIDModel


class WorkoutFolder(PrefixedIDModel):
    id_prefix = "wfd"

    created_by = models.ForeignKey(User, on_delete=models.CASCADE, related_name="workout_folders")
    name = models.CharField(max_length=100)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["created_by", "name"], name="unique_athlete_folder_name"),
        ]

    def __str__(self) -> str:
        return self.name


class Workout(PrefixedIDModel):
    id_prefix = "wkt"

    SPORT_CHOICES = [
        ("bike", "Bike"),
        ("run", "Run"),
    ]

    # Internal-only — not part of the public Workout schema in openapi.yaml.
    # Used purely for list-scoping ("the athlete's workout library") and to
    # resolve the target athlete for permission checks on detail/update/delete.
    # Never serialize this field.
    created_by = models.ForeignKey(User, on_delete=models.CASCADE, related_name="workouts")

    name = models.CharField(max_length=200)
    sport = models.CharField(max_length=10, choices=SPORT_CHOICES)
    # Free-text classification (e.g. "vo2"). Not settable via any documented
    # request body — clients can never write it, so it just stays blank.
    type = models.CharField(max_length=50, blank=True, default="")
    duration = models.IntegerField(default=0)
    tss = models.IntegerField(default=0)
    folder = models.ForeignKey(WorkoutFolder, null=True, blank=True, on_delete=models.SET_NULL, related_name="workouts")
    tags = models.JSONField(default=list, blank=True)
    # Flattened per-leaf average target intensity, recomputed alongside duration/tss
    # whenever steps are replaced — cheap chart data for library cards/rows without
    # shipping the full step tree in the list response.
    chart_preview = models.JSONField(default=list, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    def __str__(self) -> str:
        return self.name


class WorkoutStep(models.Model):
    """Not fetched by its own id, so it uses a plain BigAutoField per the
    core.models.PrefixedIDModel convention. `order` (scoped to `parent`, or to
    `workout` for top-level steps) makes the nested `steps` tree on the workout
    detail response reconstructable.

    A step is either a leaf (`kind` in warmup/block/rec/cool — has an
    `end_type`/`duration`/`distance` and a target) or a `repeat` group (has
    `repeat` and owns child rows via `parent`, which may themselves be nested
    `repeat` groups). See the CHECK constraint below for the exact split.
    """

    KIND_CHOICES = [
        ("warmup", "Warmup"),
        ("block", "Block"),
        ("rec", "Recovery"),
        ("cool", "Cooldown"),
        ("repeat", "Repeat"),
    ]
    END_TYPE_CHOICES = [
        ("time", "Time"),
        ("distance", "Distance"),
        ("manual", "Manual"),
    ]
    TARGET_TYPE_CHOICES = [
        ("power", "Power"),
        ("hr", "Heart rate"),
        ("pace", "Pace"),
        ("cadence", "Cadence"),
        ("open", "Open"),
    ]
    TARGET2_TYPE_CHOICES = [
        ("cadence", "Cadence"),
        ("none", "None"),
    ]
    POWER_UNIT_CHOICES = [
        ("pct_ftp", "% FTP"),
        ("watts", "Watts"),
    ]

    workout = models.ForeignKey(Workout, on_delete=models.CASCADE, related_name="steps")
    parent = models.ForeignKey("self", null=True, blank=True, on_delete=models.CASCADE, related_name="children")
    order = models.IntegerField(default=0)
    kind = models.CharField(max_length=10, choices=KIND_CHOICES)
    # blank (not null) for `repeat` rows — see repeat_step_has_no_leaf_fields below.
    end_type = models.CharField(max_length=10, choices=END_TYPE_CHOICES, blank=True, default="")
    duration = models.IntegerField(null=True, blank=True)
    distance = models.IntegerField(null=True, blank=True)
    target_type = models.CharField(max_length=10, choices=TARGET_TYPE_CHOICES, blank=True, default="")
    target_low = models.FloatField(null=True, blank=True)
    target_high = models.FloatField(null=True, blank=True)
    # Only meaningful when target_type == "power" - which unit target_low/target_high are in
    # (% of FTP/critical_run_power, or absolute watts). Harmless default on every other row,
    # same convention as target2_type's default of "none" on rows where it doesn't apply.
    power_unit = models.CharField(max_length=10, choices=POWER_UNIT_CHOICES, blank=True, default="pct_ftp")
    target2_type = models.CharField(max_length=10, choices=TARGET2_TYPE_CHOICES, default="none")
    target2_low = models.FloatField(null=True, blank=True)
    target2_high = models.FloatField(null=True, blank=True)
    repeat = models.IntegerField(default=1)
    note = models.TextField(blank=True, default="")

    class Meta:
        ordering = ["order"]
        constraints = [
            models.UniqueConstraint(fields=["workout", "parent", "order"], name="unique_step_order_per_parent"),
            models.CheckConstraint(
                condition=(
                    models.Q(
                        kind="repeat",
                        end_type="",
                        duration__isnull=True,
                        distance__isnull=True,
                        target_type="",
                    )
                    | ~models.Q(kind="repeat")
                ),
                name="repeat_step_has_no_leaf_fields",
            ),
        ]

    def __str__(self) -> str:
        return f"{self.workout_id} step {self.order} ({self.kind})"
