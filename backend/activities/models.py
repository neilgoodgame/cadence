from django.db import models

from accounts.models import User
from core.models import PrefixedIDModel
from gear.models import Bike, Shoe
from workouts.models import Workout


class Activity(PrefixedIDModel):
    id_prefix = "act"

    SPORT_CHOICES = [
        ("bike", "Bike"),
        ("run", "Run"),
        ("swim", "Swim"),
        ("walk", "Walk"),
    ]
    ENVIRONMENT_CHOICES = [
        ("outdoor", "Outdoor"),
        ("indoor", "Indoor"),
    ]
    DISTANCE_SOURCE_CHOICES = [
        ("gps", "GPS"),
        ("footpod", "Footpod"),
        ("trainer", "Trainer"),
        ("manual", "Manual"),
    ]

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="activities")
    sport = models.CharField(max_length=10, choices=SPORT_CHOICES)
    environment = models.CharField(max_length=10, choices=ENVIRONMENT_CHOICES, default="outdoor")
    # Always false when environment is indoor — enforced at upload time (Phase 5),
    # just a plain stored field for now.
    has_gps = models.BooleanField(default=False)
    name = models.CharField(max_length=200)
    start_date = models.DateTimeField()
    source = models.CharField(max_length=100, blank=True, default="")
    moving_time = models.IntegerField(default=0)
    distance_km = models.FloatField(default=0)
    distance_source = models.CharField(max_length=10, choices=DISTANCE_SOURCE_CHOICES, default="gps")
    avg_power = models.IntegerField(null=True, blank=True)
    norm_power = models.IntegerField(null=True, blank=True)
    intensity = models.FloatField(null=True, blank=True)
    tss = models.IntegerField(default=0)
    avg_hr = models.IntegerField(null=True, blank=True)
    max_hr = models.IntegerField(null=True, blank=True)
    ascent = models.IntegerField(null=True, blank=True)
    start_weight_kg = models.FloatField(null=True, blank=True)
    end_weight_kg = models.FloatField(null=True, blank=True)
    fluids_ml = models.IntegerField(null=True, blank=True)
    avg_air_temp = models.FloatField(null=True, blank=True)
    avg_humidity = models.IntegerField(null=True, blank=True)
    # Garmin's Firstbeat-derived training load metrics, from a FIT session
    # message (no GPX/TCX equivalent). Device-computed, never user-settable.
    aerobic_training_effect = models.FloatField(null=True, blank=True)
    anaerobic_training_effect = models.FloatField(null=True, blank=True)
    training_effect_label = models.CharField(max_length=20, blank=True, default="")
    workout = models.ForeignKey(Workout, null=True, blank=True, on_delete=models.SET_NULL, related_name="activities")
    bike = models.ForeignKey(Bike, null=True, blank=True, on_delete=models.SET_NULL, related_name="activities")
    shoe = models.ForeignKey(Shoe, null=True, blank=True, on_delete=models.SET_NULL, related_name="activities")
    tags: "models.ManyToManyField[Tag, ActivityTag]" = models.ManyToManyField(
        "Tag", through="ActivityTag", related_name="activities", blank=True
    )

    class Meta:
        ordering = ["-start_date"]

    def __str__(self) -> str:
        return self.name


class Lap(models.Model):
    """Not fetched by its own id, so it uses a plain BigAutoField per the
    core.models.PrefixedIDModel convention."""

    activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="laps")
    index = models.IntegerField()
    duration = models.IntegerField()
    distance_km = models.FloatField()
    avg_hr = models.IntegerField(null=True, blank=True)
    avg_power = models.IntegerField(null=True, blank=True)

    class Meta:
        ordering = ["index"]

    def __str__(self) -> str:
        return f"{self.activity_id} lap {self.index}"


class Tag(PrefixedIDModel):
    id_prefix = "tag"

    ORIGIN_CHOICES = [
        ("manual", "Manual"),
        ("auto", "Auto"),
    ]

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="tags")
    name = models.CharField(max_length=100)
    origin = models.CharField(max_length=10, choices=ORIGIN_CHOICES, default="manual")
    color = models.CharField(max_length=20, blank=True, default="")

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["athlete", "name"], name="unique_athlete_tag_name"),
        ]

    def __str__(self) -> str:
        return self.name


class ActivityTag(models.Model):
    """Join table — no id in its own right, never fetched independently."""

    activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="activity_tags")
    tag = models.ForeignKey(Tag, on_delete=models.CASCADE, related_name="activity_tags")

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["activity", "tag"], name="unique_activity_tag"),
        ]

    def __str__(self) -> str:
        return f"{self.activity_id} -> {self.tag_id}"


class Record(models.Model):
    """The 1 Hz time-series stream. A TimescaleDB hypertable partitioned on `ts`
    (see the 0002 migration's raw-SQL conversion) — `t` (seconds offset from
    activity.start_date) is kept alongside `ts` purely for convenient ordering/
    indexing without re-deriving it from the activity on every read.

    Never fetched by its own id, so the surrogate BigAutoField's uniqueness is
    dropped in favor of (activity, ts) once the hypertable conversion runs —
    Timescale requires every unique/PK constraint to include the partitioning
    column, and id-alone can't satisfy that.
    """

    activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="records")
    t = models.IntegerField()
    ts = models.DateTimeField()
    power = models.IntegerField(null=True, blank=True)
    heartrate = models.IntegerField(null=True, blank=True)
    cadence = models.IntegerField(null=True, blank=True)
    altitude = models.FloatField(null=True, blank=True)
    lat = models.FloatField(null=True, blank=True)
    lng = models.FloatField(null=True, blank=True)
    speed = models.FloatField(null=True, blank=True)
    distance_km = models.FloatField(null=True, blank=True)
    air_temp = models.FloatField(null=True, blank=True)
    humidity = models.IntegerField(null=True, blank=True)
    skin_temp = models.FloatField(null=True, blank=True)
    core_temp = models.FloatField(null=True, blank=True)
    heat_strain = models.FloatField(null=True, blank=True)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["activity", "ts"], name="unique_activity_record_ts"),
        ]
        ordering = ["t"]

    def __str__(self) -> str:
        return f"{self.activity_id} @ {self.t}s"


class DurationCurve(models.Model):
    """Not fetched by its own id, so it uses a plain BigAutoField per the
    core.models.PrefixedIDModel convention."""

    METRIC_CHOICES = [
        ("power", "Power"),
        ("heartrate", "Heart rate"),
    ]

    activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="duration_curves")
    metric = models.CharField(max_length=10, choices=METRIC_CHOICES)
    extends_to = models.IntegerField()
    points = models.JSONField(default=dict)

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["activity", "metric"], name="unique_activity_curve_metric"),
        ]

    def __str__(self) -> str:
        return f"{self.activity_id} {self.metric} curve"


class BestEffort(models.Model):
    """One row per (athlete, kind, window) — the current personal record,
    upserted whenever a new activity's curve beats it. Not fetched by its own
    id, so it uses a plain BigAutoField per the core.models.PrefixedIDModel
    convention.
    """

    KIND_CHOICES = [
        ("cycling_power", "Cycling power"),
        ("running_pace", "Running pace"),
        ("running_power", "Running power"),
    ]

    athlete = models.ForeignKey(User, on_delete=models.CASCADE, related_name="best_efforts")
    kind = models.CharField(max_length=20, choices=KIND_CHOICES)
    window = models.CharField(max_length=20)
    value = models.FloatField()
    unit = models.CharField(max_length=20)
    date = models.DateField()
    activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="best_efforts")

    class Meta:
        constraints = [
            models.UniqueConstraint(fields=["athlete", "kind", "window"], name="unique_athlete_kind_window"),
        ]
        ordering = ["kind", "window"]

    def __str__(self) -> str:
        return f"{self.athlete_id} {self.kind} {self.window}"
