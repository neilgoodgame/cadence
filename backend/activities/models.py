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
        ("row", "Row"),
        # Only created by multisport FIT imports: the parent spans the whole file
        # ("multisport") and each leg, transitions included, is a child activity
        # linked via parent_activity.
        ("multisport", "Multisport"),
        ("transition", "Transition"),
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
    # Recording device from the file's metadata (FIT file_id), e.g. "Zwift" or
    # "Garmin Edge 830". Empty when the format doesn't carry it (GPX/TCX).
    device = models.CharField(max_length=100, blank=True, default="")
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
    # Extended stats (Activity Analysis "Stats" tab). All computed once at ingest from the
    # record stream, same pattern as avg_power/max_hr above - null whenever the source data
    # needed for that one metric wasn't present (e.g. no altitude stream -> no elevation
    # fields; no power meter -> no max_power/calories). Deliberately NOT storing metrics
    # that are pure arithmetic over fields that already exist (avg W/kg, work in kJ, avg
    # speed, HR reserve % all derive from avg_power/moving_time/distance_km/avg_hr plus the
    # athlete's own profile - the frontend computes those directly).
    max_power = models.IntegerField(null=True, blank=True)
    avg_cadence = models.IntegerField(null=True, blank=True)
    max_cadence = models.IntegerField(null=True, blank=True)
    max_speed = models.FloatField(null=True, blank=True, help_text="km/h")
    total_descent = models.IntegerField(null=True, blank=True, help_text="metres")
    elevation_min = models.IntegerField(null=True, blank=True, help_text="metres")
    elevation_max = models.IntegerField(null=True, blank=True, help_text="metres")
    # Power-based estimate only (work_kJ / 0.24, a standard cycling efficiency
    # approximation) - deliberately not falling back to an HR-based estimate for
    # power-less activities, which would be a much rougher guess.
    calories = models.IntegerField(null=True, blank=True, help_text="kcal")
    # Edwards' TRIMP: sum over HR zones of (minutes in zone * zone number 1-5). Chosen
    # over Banister's original formula specifically because it needs no resting-HR
    # baseline - reuses the same HR zone set already computed for hrTSS.
    trimp = models.FloatField(null=True, blank=True)
    # % of power from the left leg, from the FIT record stream's left_right_balance field
    # (dual-sided/balance-capable power meters only - null for everything else, which is
    # most activities). Right % = 100 - this.
    avg_left_balance_pct = models.FloatField(null=True, blank=True)
    workout = models.ForeignKey(Workout, null=True, blank=True, on_delete=models.SET_NULL, related_name="activities")
    bike = models.ForeignKey(Bike, null=True, blank=True, on_delete=models.SET_NULL, related_name="activities")
    shoe = models.ForeignKey(Shoe, null=True, blank=True, on_delete=models.SET_NULL, related_name="activities")
    # Set on the per-leg children of a multisport activity; null everywhere else.
    parent_activity = models.ForeignKey(
        "self", null=True, blank=True, on_delete=models.CASCADE, related_name="child_activities"
    )
    # Set on a duplicate recording of another activity (the "primary"); null everywhere
    # else. Only the primary counts toward training load. SET_NULL on delete: a duplicate
    # is a full activity in its own right, not a dependent like a multisport leg.
    primary_activity = models.ForeignKey(
        "self", null=True, blank=True, on_delete=models.SET_NULL, related_name="duplicate_activities"
    )
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
    """The 1 Hz time-series stream. A natively RANGE-partitioned table on `ts`
    (see the 0003 migration's raw-SQL table recreation, plus activities/tasks.py's
    ensure_record_partitions for rolling the partition range forward over time) —
    `t` (seconds offset from activity.start_date) is kept alongside `ts` purely for
    convenient ordering/indexing without re-deriving it from the activity on every
    read.

    Never fetched by its own id, so the surrogate BigAutoField's uniqueness is
    dropped in favor of (activity, ts) once the partitioning migration runs —
    Postgres requires every unique/PK constraint on a partitioned table to include
    the partitioning column, and id-alone can't satisfy that.
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
    """One row per (athlete, kind, window, activity) — the top-N personal records
    per window, ranked by value. Not fetched by its own id, so it uses a plain
    BigAutoField per the core.models.PrefixedIDModel convention.
    """

    KIND_CHOICES = [
        ("cycling_hr", "Cycling heart rate"),
        ("cycling_power", "Cycling power"),
        ("running_hr", "Running heart rate"),
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
            models.UniqueConstraint(
                fields=["athlete", "kind", "window", "activity"], name="unique_athlete_kind_window_activity"
            ),
        ]
        ordering = ["kind", "window", "-value"]

    def __str__(self) -> str:
        return f"{self.athlete_id} {self.kind} {self.window}"


class ActivityComment(PrefixedIDModel):
    """A comment on an activity, from the athlete or anyone with read access to their data
    (a coach or viewer share) — a lightweight social feature, not a data mutation, so it's
    gated by user_may_read rather than user_may_write (see activities/views.py). Role
    (athlete vs coach) is derived at read time from the relationship to the activity's
    owner, not stored - it can change (a share could be revoked) and storing it would let
    it drift from the truth.
    """

    id_prefix = "cmt"

    activity = models.ForeignKey(Activity, on_delete=models.CASCADE, related_name="comments")
    author = models.ForeignKey(User, on_delete=models.CASCADE, related_name="activity_comments")
    text = models.TextField()
    created = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["created"]

    def __str__(self) -> str:
        return f"{self.author_id} on {self.activity_id}"
